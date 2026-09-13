package productfactory.workflow

import productfactory.config.FactoryEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class EnvironmentProviderTest {

    @Test
    fun `factory environment maps to execution environment type`() {
        assertEquals(EnvironmentType.LOCAL_DOCKER, FactoryEnvironmentTypeMapper.toEnvironmentType(FactoryEnvironment.LOCAL))
        assertEquals(EnvironmentType.LOCAL_DOCKER, FactoryEnvironmentTypeMapper.toEnvironmentType(FactoryEnvironment.CI))
        assertEquals(EnvironmentType.REMOTE_SSH, FactoryEnvironmentTypeMapper.toEnvironmentType(FactoryEnvironment.K8S))
    }

    @Test
    fun `local docker provider returns docker context with compose and workspace params`() {
        val context = LocalDockerEnvironmentProvider().provide(runId = "Run 1", envType = EnvironmentType.LOCAL_DOCKER)

        assertEquals("Run 1", context.runId)
        assertEquals(EnvironmentType.LOCAL_DOCKER, context.environmentType)
        assertEquals("docker", context.executionMode)
        assertTrue(context.parameters.containsKey("compose_file_path"))
        assertTrue(context.parameters.containsKey("workspace_path"))
        assertTrue(context.parameters.containsKey("execution_strategy"))
        assertEquals(context.workingDirectory, context.parameters["workspace_path"])
    }

    @Test
    fun `remote ssh provider enables ssh execution context when host user key are set`() {
        val context = RemoteSshEnvironmentProvider(
            host = "build-node.internal",
            user = "deployer",
            keyPath = "/run/secrets/deployer_key",
        ).provide(runId = "run-2", envType = EnvironmentType.REMOTE_SSH)

        assertEquals("run-2", context.runId)
        assertEquals(EnvironmentType.REMOTE_SSH, context.environmentType)
        assertEquals("ssh", context.executionMode)
        assertEquals("enabled", context.parameters["ssh.runner_status"])
        assertEquals("key_path", context.parameters["ssh.auth_mode"])
        assertEquals("RemoteSshExecutionContext", context.executionContext::class.simpleName)
    }

    @Test
    fun `remote ssh provider falls back to runbook mode when config is incomplete`() {
        val context = RemoteSshEnvironmentProvider(
            host = "build-node.internal",
            user = "deployer",
            keyPath = null,
        ).provide(runId = "run-3", envType = EnvironmentType.REMOTE_SSH)

        assertEquals("disabled", context.parameters["ssh.runner_status"])
        assertEquals("placeholder_unset", context.parameters["ssh.auth_mode"])
        val result = context.executionContext.runCommand(
            command = listOf("echo", "test"),
            workingDirectory = null,
            timeoutMs = 1000L,
            env = emptyMap(),
        )
        assertFalse(result.started)
        assertTrue((result.errorMessage ?: "").contains("remote-ssh disabled"))
    }
}
