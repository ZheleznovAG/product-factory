package productfactory.workflow

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class SecurityRunnerTest {

    @Test
    fun `run returns skipped when repo directory does not exist`() {
        val workspace = Files.createTempDirectory("security-runner").toAbsolutePath()
        val result = SecurityRunner.run(workspace, "nonexistent-repo")
        assertTrue(result.skipped)
        assertTrue(result.passed)
    }
}
