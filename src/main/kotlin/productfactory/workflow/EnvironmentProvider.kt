package productfactory.workflow

import productfactory.config.FactoryEnvironment
import java.nio.file.Path

/**
 * Тип окружения для выполнения шагов через Tool Executor.
 */
enum class EnvironmentType(val value: String) {
    LOCAL_DOCKER("local-docker"),
    REMOTE_SSH("remote-ssh"),
}

/**
 * Минимальный контекст выполнения шага в окружении.
 * Поля можно расширять по мере появления реальных backend-адаптеров.
 */
data class EnvironmentContext(
    val runId: String,
    val environmentType: EnvironmentType,
    val executionMode: String,
    val executionContext: ExecutionContext = HostExecutionContext,
    val workingDirectory: String? = null,
    val parameters: Map<String, String> = emptyMap(),
)

/**
 * Резолвит конфигурацию окружения для конкретного run.
 */
fun interface EnvironmentProvider {
    fun provide(runId: String, envType: EnvironmentType): EnvironmentContext
}

/**
 * Провайдер для local-docker контекста. В режиме full возвращает execution context,
 * который запускает команды внутри Docker-контейнера runner image.
 */
class LocalDockerEnvironmentProvider : EnvironmentProvider {
    private val workspaceRoot = Path.of(System.getenv("FACTORY_WORKSPACE_DIR") ?: "workspace")
    private val composeFile = Path.of(System.getenv("FACTORY_LOCAL_DOCKER_COMPOSE_FILE") ?: "deploy/docker-compose.yml")
    private val workspacePerRun = (System.getenv("FACTORY_LOCAL_DOCKER_WORKSPACE_PER_RUN") ?: "true").equals("true", ignoreCase = true)
    private val k8sProvider = System.getenv("FACTORY_LOCAL_K8S_PROVIDER")?.trim()?.takeIf { it.isNotBlank() }
    private val fullExecution = (System.getenv("FACTORY_LOCAL_DOCKER_EXECUTION") ?: "runbook").equals("full", ignoreCase = true)
    private val dockerRunnerImage = System.getenv("FACTORY_LOCAL_DOCKER_RUNNER_IMAGE")?.trim()?.takeIf { it.isNotBlank() }
        ?: "product-factory-tool-runner:latest"

    override fun provide(runId: String, envType: EnvironmentType): EnvironmentContext {
        require(envType == EnvironmentType.LOCAL_DOCKER) {
            "LocalDockerEnvironmentProvider supports only ${EnvironmentType.LOCAL_DOCKER.value}"
        }
        val workspacePath = resolveWorkspacePath(runId)
        val executionContext = if (fullExecution) {
            LocalDockerExecutionContext(
                workspaceRoot = workspaceRoot,
                workspacePath = Path.of(workspacePath),
                dockerImage = dockerRunnerImage,
            )
        } else {
            HostExecutionContext
        }
        val params = buildMap {
            put("compose_file_path", composeFile.toString())
            put("workspace_path", workspacePath)
            put("execution_strategy", if (fullExecution) "full" else "runbook")
            put("runner_image", dockerRunnerImage)
            k8sProvider?.let { put("k8s_provider", it) }
        }
        return EnvironmentContext(
            runId = runId,
            environmentType = envType,
            executionMode = "docker",
            executionContext = executionContext,
            workingDirectory = workspacePath,
            parameters = params,
        )
    }

    private fun resolveWorkspacePath(runId: String): String {
        if (!workspacePerRun) return workspaceRoot.toString()
        val sanitizedRunId = runId.trim().ifBlank { "run" }
            .lowercase()
            .replace(Regex("[^a-z0-9._-]"), "-")
            .trim('-')
            .ifBlank { "run" }
        return workspaceRoot.resolve(sanitizedRunId).toString()
    }
}

/**
 * Минимальный провайдер remote-ssh: при наличии host/user/key подключает RemoteSshExecutionContext
 * для запуска одной команды через SSH. При неполной конфигурации возвращает runbook-заглушку.
 */
class RemoteSshEnvironmentProvider(
    private val host: String? = env("FACTORY_REMOTE_SSH_HOST"),
    private val user: String? = env("FACTORY_REMOTE_SSH_USER"),
    private val keyPath: String? = env("FACTORY_REMOTE_SSH_KEY_PATH"),
) : EnvironmentProvider {

    override fun provide(runId: String, envType: EnvironmentType): EnvironmentContext {
        require(envType == EnvironmentType.REMOTE_SSH) {
            "RemoteSshEnvironmentProvider supports only ${EnvironmentType.REMOTE_SSH.value}"
        }
        val executionContext = resolveExecutionContext()
        val isEnabled = executionContext is RemoteSshExecutionContext
        return EnvironmentContext(
            runId = runId,
            environmentType = envType,
            executionMode = "ssh",
            executionContext = executionContext,
            parameters = mapOf(
                "ssh.host" to (host ?: "placeholder-host"),
                "ssh.user" to (user ?: "placeholder-user"),
                "ssh.auth_mode" to if (isEnabled) "key_path" else "placeholder_unset",
                "ssh.key_path" to (keyPath ?: "unset"),
                "ssh.exec.command_template" to "ssh {auth_flags} {user}@{host} -- {command}",
                "ssh.runner_status" to if (isEnabled) "enabled" else "disabled",
            ),
        )
    }

    private fun resolveExecutionContext(): ExecutionContext {
        if (host.isNullOrBlank() || user.isNullOrBlank() || keyPath.isNullOrBlank()) {
            return RunbookExecutionContext("remote-ssh disabled: set FACTORY_REMOTE_SSH_HOST/USER/KEY_PATH")
        }
        return RemoteSshExecutionContext(
            host = host,
            user = user,
            keyPath = keyPath,
        )
    }
}

private fun env(name: String): String? = System.getenv(name)?.trim()?.takeIf { it.isNotBlank() }

/**
 * Комбинирует провайдеры по типам окружения.
 */
class DefaultEnvironmentProvider(
    private val localDockerProvider: EnvironmentProvider = LocalDockerEnvironmentProvider(),
    private val remoteSshProvider: EnvironmentProvider = RemoteSshEnvironmentProvider(),
) : EnvironmentProvider {
    override fun provide(runId: String, envType: EnvironmentType): EnvironmentContext {
        return when (envType) {
            EnvironmentType.LOCAL_DOCKER -> localDockerProvider.provide(runId, envType)
            EnvironmentType.REMOTE_SSH -> remoteSshProvider.provide(runId, envType)
        }
    }
}

/**
 * Текущая безопасная заглушка: не выполняет внешние действия и возвращает только
 * конфиг/параметры, которые затем может использовать Tool Executor.
 */
class StubEnvironmentProvider : EnvironmentProvider {
    private val delegate = DefaultEnvironmentProvider(
        localDockerProvider = LocalDockerEnvironmentProvider(),
        remoteSshProvider = RemoteSshEnvironmentProvider(),
    )

    override fun provide(runId: String, envType: EnvironmentType): EnvironmentContext = delegate.provide(runId, envType)
}

/**
 * Связка существующего профиля запуска фабрики и нового типа окружения шагов.
 */
object FactoryEnvironmentTypeMapper {
    fun toEnvironmentType(factoryEnvironment: FactoryEnvironment): EnvironmentType {
        return when (factoryEnvironment) {
            FactoryEnvironment.LOCAL, FactoryEnvironment.CI -> EnvironmentType.LOCAL_DOCKER
            FactoryEnvironment.K8S -> EnvironmentType.REMOTE_SSH
        }
    }
}
