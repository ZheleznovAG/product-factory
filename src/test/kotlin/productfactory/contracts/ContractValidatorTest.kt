package productfactory.contracts

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContractValidatorTest {

    private val validator = ContractValidator(schemaDirectory = Path.of("contracts", "schemas"))

    @Test
    fun `validateDirectory returns valid for conforming contracts`() {
        val dir = createTempDirectory("contracts-valid")
        writeValidContracts(dir)

        val result = validator.validateDirectory(dir)

        assertTrue(result.valid)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `validateDirectory returns deterministic sorted errors`() {
        val dir = createTempDirectory("contracts-invalid")
        writeValidContracts(dir)
        dir.resolve("product.yaml").writeText(
            """
            apiVersion: productfactory.io/v1
            kind: ProductSpec
            metadata:
              productId: catalog-service
              owner: solo-dev
            product:
              type: api-service
              name: catalog-service
              description: desc
              interfaces:
                http:
                  basePath: /api/v1
                  auth: bearer-jwt
            data:
              stores:
                - name: catalog-db
                  kind: postgres
                  schema: catalog
            nonFunctional:
              availabilityTarget: "99.5"
              maxP95LatencyMs: 250
              maxErrorRatePercent: 0.5
            """.trimIndent(),
        )

        val result = validator.validateDirectory(dir)

        assertFalse(result.valid)
        assertTrue(result.errors.isNotEmpty())
        val first = result.errors.first()
        assertEquals("product.yaml", first.contractFile)
        assertTrue(first.field.isNotBlank())
    }

    private fun writeValidContracts(dir: Path) {
        dir.createDirectories()

        dir.resolve("product.yaml").writeText(
            """
            apiVersion: productfactory.io/v1
            kind: ProductSpec
            metadata:
              productId: catalog-service
              owner: solo-dev
              createdAt: 2026-02-20
            product:
              type: api-service
              name: catalog-service
              description: desc
              interfaces:
                http:
                  basePath: /api/v1
                  auth: bearer-jwt
            data:
              stores:
                - name: catalog-db
                  kind: postgres
                  schema: catalog
            nonFunctional:
              availabilityTarget: "99.5"
              maxP95LatencyMs: 250
              maxErrorRatePercent: 0.5
            """.trimIndent(),
        )

        dir.resolve("constraints.yaml").writeText(
            """
            apiVersion: productfactory.io/v1
            kind: Constraints
            security:
              secrets:
                storage: env-and-vault
                loggingRedaction: true
              dataClassification:
                pii: none
                retentionDays: 30
              allowedOutboundDomains:
                - api.openai.com
            platform:
              runtime: docker-compose
              os: linux-amd64
              containerRegistry: ghcr.io
            delivery:
              ci: github-actions
              cd: gitops-staging
            """.trimIndent(),
        )

        dir.resolve("quality_profile.yaml").writeText(
            """
            apiVersion: productfactory.io/v1
            kind: QualityProfile
            testing:
              unitCoverageMinPercent: 70
              integrationTestsRequired: true
              smokeTestsRequired: true
            securityGates:
              sbom:
                format: cyclonedx-json
                required: true
              vulnerabilityScan:
                tool: trivy
                failOnSeverity:
                  - CRITICAL
                  - HIGH
              signing:
                tool: cosign
                required: true
            observability:
              tracing:
                required: true
                protocol: otlp-http
              metrics:
                required: true
            """.trimIndent(),
        )

        dir.resolve("risk_profile.yaml").writeText(
            """
            apiVersion: productfactory.io/v1
            kind: RiskProfile
            riskTier: medium
            agentPolicy:
              approvalsRequiredFor:
                - write_repository
              budgets:
                maxLlmtokensPerRun: 250000
                maxToolCallsPerRun: 40
                maxWallClockSecondsPerRun: 900
            threatModel:
              llmTop10Focus:
                - prompt_injection
            """.trimIndent(),
        )

        dir.resolve("target_stack.yaml").writeText(
            """
            apiVersion: productfactory.io/v1
            kind: TargetStack
            language:
              name: kotlin
              framework: ktor
            packaging:
              container: docker
            database:
              primary: postgres
            observability:
              tracing: opentelemetry
              metrics: prometheus
            """.trimIndent(),
        )
    }
}
