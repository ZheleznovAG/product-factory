package productfactory

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Tracer
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import productfactory.agent.AgentCodegen
import productfactory.agent.AgentPlanner
import productfactory.agent.LlmAgentCodegen
import productfactory.agent.LlmAgentPlanner
import productfactory.agent.StubAgentCodegen
import productfactory.agent.StubAgentPlanner
import productfactory.auth.ApiAuthConfig
import productfactory.auth.installApiAuthGuard
import productfactory.cli.ProductFactoryCli
import productfactory.config.FactoryEnvironment
import productfactory.contracts.ContractValidator
import productfactory.neural.HttpNeuralServiceClient
import productfactory.neural.NeuralFallbackMatrix
import productfactory.neural.NeuralServiceClient
import productfactory.observability.OpenTelemetryFactory
import productfactory.policy.PolicyCheck
import productfactory.profile.FileProfileStore
import productfactory.profile.ProfileStore
import productfactory.profile.ProfileStoreConfig
import productfactory.intent.IntentClarification
import productfactory.intent.IntentClarifier
import productfactory.intent.IntentGenerator
import productfactory.intent.IntentCandidatesGenerator
import productfactory.intent.LlmIntentGenerator
import productfactory.intent.LlmIntentCandidatesGenerator
import productfactory.intent.LlmIntentClarification
import productfactory.rag.NeuralRagEmbeddingClient
import productfactory.rag.PgVectorPlannerContextProvider
import productfactory.rag.PlannerContextProvider
import productfactory.rag.RagFlags
import productfactory.rag.RagIndexConfig
import productfactory.workflow.AuditLog
import productfactory.workflow.ApprovalStore
import productfactory.workflow.FileApprovalStore
import productfactory.workflow.AskUserStore
import productfactory.workflow.FileAskUserStore
import productfactory.workflow.ArtifactRegistry
import productfactory.workflow.FileArtifactRegistry
import productfactory.workflow.FileAuditLog
import productfactory.workflow.FilePeriodUsageStore
import productfactory.workflow.FactoryWorkflowExecution
import productfactory.workflow.PeriodUsageStore
import productfactory.workflow.CostBudgetGuard
import productfactory.workflow.WorkflowRunner
import productfactory.workflow.DefaultEnvironmentProvider
import productfactory.workflow.TemplateSprintAdrDraftGenerator
import productfactory.workflow.temporal.TemporalFactoryWorker
import productfactory.api.factoryRoutes
import io.temporal.client.WorkflowClient
import io.temporal.worker.WorkerFactory
import productfactory.workflow.storage.ArtifactStorage
import productfactory.workflow.storage.S3ArtifactStorage
import productfactory.workflow.tools.DockerToolExecutor
import productfactory.workflow.tools.SandboxToolExecutor
import productfactory.workflow.tools.ToolExecutor
import productfactory.workflow.tools.EnvSecretProvider
import productfactory.workflow.tools.loadToolRegistry
import java.nio.file.Path
import kotlin.system.exitProcess

private fun buildArtifactStorage(): ArtifactStorage? {
    val bucket = System.getenv("ARTIFACT_STORAGE_BUCKET")?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return S3ArtifactStorage(bucket)
}

private data class AgentRuntimeComponents(
    val planner: AgentPlanner,
    val codegen: AgentCodegen,
    val intentClarifier: IntentClarifier,
    val intentGenerator: IntentGenerator,
    val intentCandidatesGenerator: IntentCandidatesGenerator,
)

private fun createAgentRuntimeComponents(auditLog: AuditLog): AgentRuntimeComponents {
    val neuralMatrix = NeuralFallbackMatrix.fromEnvOrYaml()
    val primaryProvider = neuralMatrix.primaryProvider()
    val neuralServiceUrl = primaryProvider?.baseUrl
    val neuralServiceApiKey = primaryProvider?.apiKey
    val neuralClient: NeuralServiceClient? = if (neuralMatrix.isConfigured()) {
        HttpNeuralServiceClient(fallbackMatrix = neuralMatrix)
    } else {
        null
    }
    val plannerContextProvider: PlannerContextProvider? = run {
        if (neuralClient == null || !RagFlags.plannerContextEnabled()) return@run null
        val ragConfig = RagIndexConfig.fromEnv() ?: return@run null
        val embeddingClient = NeuralRagEmbeddingClient(
            baseUrl = neuralServiceUrl ?: return@run null,
            apiKey = neuralServiceApiKey,
            model = ragConfig.embeddingModel,
            expectedDim = ragConfig.embeddingDim,
        )
        PgVectorPlannerContextProvider(
            config = ragConfig,
            embeddingClient = embeddingClient,
        )
    }
    val planner: AgentPlanner = if (neuralClient != null) {
        LlmAgentPlanner(
            neuralClient = neuralClient,
            auditLog = auditLog,
            contextProvider = plannerContextProvider,
        )
    } else {
        StubAgentPlanner()
    }
    val codegen: AgentCodegen = if (neuralClient != null) {
        LlmAgentCodegen(neuralClient = neuralClient, auditLog = auditLog)
    } else {
        StubAgentCodegen()
    }
    val intentGenerator: IntentGenerator = if (neuralClient != null) {
        LlmIntentGenerator(
            neuralClient = neuralClient,
            fallbackGenerator = IntentGenerator(),
            neuralServiceUrl = neuralServiceUrl,
        )
    } else {
        IntentGenerator()
    }
    val intentCandidatesGenerator: IntentCandidatesGenerator = if (neuralClient != null) {
        LlmIntentCandidatesGenerator(
            neuralClient = neuralClient,
            fallbackGenerator = IntentCandidatesGenerator(),
            neuralServiceUrl = neuralServiceUrl,
        )
    } else {
        IntentCandidatesGenerator()
    }
    val fallbackClarifier = IntentClarification(intentGenerator = intentGenerator)
    val intentClarifier: IntentClarifier = if (neuralClient != null) {
        LlmIntentClarification(
            neuralClient = neuralClient,
            fallbackClarifier = fallbackClarifier,
            neuralServiceUrl = neuralServiceUrl,
        )
    } else {
        fallbackClarifier
    }
    return AgentRuntimeComponents(
        planner = planner,
        codegen = codegen,
        intentClarifier = intentClarifier,
        intentGenerator = intentGenerator,
        intentCandidatesGenerator = intentCandidatesGenerator,
    )
}

/**
 * Настраивает маршруты и плагины приложения. Вызывается из main и из тестов.
 */
fun Application.module(
    auditLog: AuditLog = FileAuditLog(),
    policyCheck: PolicyCheck = PolicyCheck(),
    approvalStore: ApprovalStore = FileApprovalStore(),
    askUserStore: AskUserStore = FileAskUserStore(),
    artifactRegistry: ArtifactRegistry = FileArtifactRegistry(),
    periodUsageStore: PeriodUsageStore = FilePeriodUsageStore(),
    profileStore: ProfileStore = FileProfileStore(),
    profileStoreConfig: ProfileStoreConfig = ProfileStoreConfig.fromEnv(),
    costBudgetGuard: CostBudgetGuard? = CostBudgetGuard.fromEnv(),
    apiAuthConfig: ApiAuthConfig = ApiAuthConfig.fromEnv(),
    tracer: Tracer = GlobalOpenTelemetry.getTracer("productfactory.workflow"),
    toolExecutor: ToolExecutor? = null,
) {
    val contractsDirectory = System.getenv("FACTORY_CONTRACTS_DIR")?.let(Path::of)
    val contractValidator = if (contractsDirectory != null) {
        ContractValidator(schemaDirectory = Path.of("contracts", "schemas"))
    } else {
        null
    }

    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    installApiAuthGuard(apiAuthConfig)
    routing {
        val artifactStorage = buildArtifactStorage()
        val registryPath = System.getenv("FACTORY_TOOLS_REGISTRY_PATH")?.trim()?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: Path.of("contracts", "tools.registry.json")
        val toolRegistry = loadToolRegistry(registryPath)
        val workspaceRoot = Path.of(System.getenv("FACTORY_WORKSPACE_DIR") ?: "workspace")
        val archetypesRoot = Path.of("archetypes")
        val secretProvider = EnvSecretProvider(toolRegistry)
        val sandbox = SandboxToolExecutor(
            auditLog = auditLog,
            artifactStorage = artifactStorage,
            toolRegistry = toolRegistry,
            secretProvider = secretProvider,
        )
        val executor = toolExecutor ?: when {
            System.getenv("FACTORY_TOOL_RUNNER")?.trim()?.equals("docker", ignoreCase = true) == true -> {
                val image = System.getenv("DOCKER_IMAGE_TOOL_RUNNER")?.trim()?.takeIf { it.isNotBlank() }
                if (image != null) {
                    DockerToolExecutor(
                        sandbox = sandbox,
                        dockerImage = image,
                        workspaceRoot = workspaceRoot,
                        archetypesRoot = archetypesRoot,
                        auditLog = auditLog,
                        artifactStorage = artifactStorage,
                        toolRegistry = toolRegistry,
                    )
                } else sandbox
            }
            else -> sandbox
        }
        val runtime = createAgentRuntimeComponents(auditLog)
        val planner = runtime.planner
        val codegen = runtime.codegen
        val intentClarifier = runtime.intentClarifier
        val intentGenerator = runtime.intentGenerator
        val intentCandidatesGenerator = runtime.intentCandidatesGenerator
        val environment = FactoryEnvironment.fromEnv()
        val environmentProvider = DefaultEnvironmentProvider()
        val runner = WorkflowRunner(
            auditLog = auditLog,
            policyCheck = policyCheck,
            approvalStore = approvalStore,
            toolExecutor = executor,
            agentPlanner = planner,
            agentCodegen = codegen,
            artifactRegistry = artifactRegistry,
            contractValidator = contractValidator,
            contractsDirectory = contractsDirectory,
            tracer = tracer,
            environment = environment,
            environmentProvider = environmentProvider,
            toolRegistryVersion = toolRegistry?.registry_version,
            sprintAdrDraftGenerator = TemplateSprintAdrDraftGenerator(
                auditLog = auditLog,
                approvalStore = approvalStore,
            ),
        )

        val temporalAddress = System.getenv("TEMPORAL_ADDRESS")?.trim()?.takeIf { it.isNotBlank() }
        val temporalNamespace = System.getenv("TEMPORAL_NAMESPACE")?.trim()?.takeIf { it.isNotBlank() } ?: TemporalFactoryWorker.NAMESPACE
        var temporalClient: WorkflowClient? = null
        var workerFactory: WorkerFactory? = null
        if (temporalAddress != null) {
            val stubs = TemporalFactoryWorker.createServiceStubs(temporalAddress)
            temporalClient = TemporalFactoryWorker.createClient(stubs, temporalNamespace)
            val execution = FactoryWorkflowExecution(
                auditLog = auditLog,
                policyCheck = policyCheck,
                approvalStore = approvalStore,
                toolExecutor = executor,
                agentPlanner = planner,
                agentCodegen = codegen,
                artifactRegistry = artifactRegistry,
                contractValidator = contractValidator,
                contractsDirectory = contractsDirectory,
                tracer = tracer,
                environment = environment,
                environmentProvider = environmentProvider,
                toolRegistryVersion = toolRegistry?.registry_version,
            )
            workerFactory = TemporalFactoryWorker.startWorker(temporalClient, execution)
        }

        factoryRoutes(
            workflowRunner = runner,
            auditLog = auditLog,
            approvalStore = approvalStore,
            askUserStore = askUserStore,
            artifactRegistry = artifactRegistry,
            periodUsageStore = periodUsageStore,
            profileStore = profileStore,
            profileStoreConfig = profileStoreConfig,
            costBudgetGuard = costBudgetGuard,
            temporalClient = temporalClient,
            intentClarifier = intentClarifier,
            intentGenerator = intentGenerator,
            intentCandidatesGenerator = intentCandidatesGenerator,
        )
    }
}

fun main(args: Array<String>) {
    val cliExitCode = ProductFactoryCli.run(args)
    if (cliExitCode != null) {
        val exitCode = cliExitCode
        exitProcess(exitCode)
    }

    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val telemetry = OpenTelemetryFactory.initialize()
    try {
        embeddedServer(Netty, port = port) {
            module(
                tracer = telemetry.openTelemetry.getTracer("productfactory.workflow"),
            )
        }.start(wait = true)
    } finally {
        telemetry.shutdown()
    }
}
