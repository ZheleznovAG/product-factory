package productfactory.workflow

import java.nio.file.Path
import java.util.concurrent.TimeUnit

data class CommandExecutionResult(
    val started: Boolean,
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    val timedOut: Boolean = false,
    val errorMessage: String? = null,
)

interface ExecutionContext {
    fun runCommand(
        command: List<String>,
        workingDirectory: Path?,
        timeoutMs: Long,
        env: Map<String, String>,
    ): CommandExecutionResult
}

object HostExecutionContext : ExecutionContext {
    override fun runCommand(
        command: List<String>,
        workingDirectory: Path?,
        timeoutMs: Long,
        env: Map<String, String>,
    ): CommandExecutionResult {
        return try {
            val processBuilder = ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
            if (workingDirectory != null) {
                processBuilder.directory(workingDirectory.toFile())
            }
            if (env.isNotEmpty()) {
                processBuilder.environment().putAll(env)
            }
            val process = processBuilder.start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
            }
            CommandExecutionResult(
                started = true,
                exitCode = if (finished) process.exitValue() else null,
                stdout = stdout,
                stderr = stderr,
                timedOut = !finished,
                errorMessage = if (finished) null else "timeout after ${timeoutMs / 1000}s",
            )
        } catch (e: Exception) {
            CommandExecutionResult(
                started = false,
                errorMessage = e.message ?: "failed to start command",
            )
        }
    }
}

class RunbookExecutionContext(
    private val message: String,
) : ExecutionContext {
    override fun runCommand(
        command: List<String>,
        workingDirectory: Path?,
        timeoutMs: Long,
        env: Map<String, String>,
    ): CommandExecutionResult {
        return CommandExecutionResult(
            started = false,
            errorMessage = message,
        )
    }
}

class LocalDockerExecutionContext(
    private val workspaceRoot: Path,
    private val workspacePath: Path,
    private val dockerImage: String,
) : ExecutionContext {
    override fun runCommand(
        command: List<String>,
        workingDirectory: Path?,
        timeoutMs: Long,
        env: Map<String, String>,
    ): CommandExecutionResult {
        val workDir = (workingDirectory ?: workspacePath).toAbsolutePath().normalize()
        val normalizedWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize()
        if (!workDir.startsWith(normalizedWorkspaceRoot)) {
            return CommandExecutionResult(
                started = false,
                errorMessage = "working directory is outside workspace root",
            )
        }
        val relative = normalizedWorkspaceRoot.relativize(workDir).toString()
        val containerWorkDir = if (relative.isBlank()) "/workspace" else "/workspace/$relative"

        val dockerCommand = mutableListOf(
            "docker", "run", "--rm",
            "-v", "${normalizedWorkspaceRoot}:/workspace",
            "-w", containerWorkDir,
        )
        env.forEach { (key, value) ->
            dockerCommand.add("-e")
            dockerCommand.add("$key=$value")
        }
        dockerCommand.add(dockerImage)
        dockerCommand.addAll(command)
        return HostExecutionContext.runCommand(
            command = dockerCommand,
            workingDirectory = null,
            timeoutMs = timeoutMs,
            env = emptyMap(),
        )
    }
}

class RemoteSshExecutionContext(
    private val host: String,
    private val user: String,
    private val keyPath: String,
) : ExecutionContext {
    override fun runCommand(
        command: List<String>,
        workingDirectory: Path?,
        timeoutMs: Long,
        env: Map<String, String>,
    ): CommandExecutionResult {
        if (command.isEmpty()) {
            return CommandExecutionResult(
                started = false,
                errorMessage = "command is empty",
            )
        }
        val remoteCommand = buildRemoteCommand(command, workingDirectory, env)
        val sshCommand = listOf(
            "ssh",
            "-i",
            keyPath,
            "-o",
            "BatchMode=yes",
            "-o",
            "StrictHostKeyChecking=accept-new",
            "$user@$host",
            "--",
            "sh",
            "-lc",
            remoteCommand,
        )
        return HostExecutionContext.runCommand(
            command = sshCommand,
            workingDirectory = null,
            timeoutMs = timeoutMs,
            env = emptyMap(),
        )
    }

    private fun buildRemoteCommand(
        command: List<String>,
        workingDirectory: Path?,
        env: Map<String, String>,
    ): String {
        val baseCommand = command.joinToString(" ") { shellQuote(it) }
        val envPrefix = env
            .filterKeys { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }
            .entries
            .joinToString(" ") { "${it.key}=${shellQuote(it.value)}" }
        val withEnv = if (envPrefix.isBlank()) baseCommand else "env $envPrefix $baseCommand"
        return if (workingDirectory != null) {
            "cd ${shellQuote(workingDirectory.toString())} && $withEnv"
        } else {
            withEnv
        }
    }

    private fun shellQuote(value: String): String {
        if (value.isEmpty()) return "''"
        return "'${value.replace("'", "'\"'\"'")}'"
    }
}
