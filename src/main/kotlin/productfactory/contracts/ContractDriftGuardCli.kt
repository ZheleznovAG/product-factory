package productfactory.contracts

import java.nio.file.Path

object ContractDriftGuardCli {
    private const val usage = "Usage: product-factory contract-drift-guard <contracts-dir> [--format text|json] [--report <output-file>] | product-factory pf contract-drift-guard <contracts-dir> [--format text|json] [--report <output-file>]"

    fun run(args: Array<String>): Int {
        val parsed = parse(args) ?: run {
            System.err.println(usage)
            return 2
        }

        val validator = ContractValidator(schemaDirectory = Path.of("contracts", "schemas"))
        val validationResult = validator.validateDirectory(parsed.contractsDirectory)
        val report = ContractDriftGuard.buildReport(
            contractsDirectory = parsed.contractsDirectory,
            validationResult = validationResult,
        )
        val reportText = when (parsed.format) {
            ReportFormat.TEXT -> ContractDriftGuard.renderText(report)
            ReportFormat.JSON -> ContractDriftGuard.renderJson(report)
        }

        println(reportText)
        parsed.outputFile?.let { ContractDriftGuard.writeReport(it, reportText) }
        if (parsed.outputFile != null) {
            println("Report written to ${parsed.outputFile.toAbsolutePath()}")
        }
        return if (report.compatible) 0 else 1
    }

    private fun parse(args: Array<String>): ParsedArgs? {
        val startIndex = when {
            args.size >= 2 && args[0] == "contract-drift-guard" -> 0
            args.size >= 3 && args[0] == "pf" && args[1] == "contract-drift-guard" -> 1
            else -> return null
        }
        val contractsDirArg = args.getOrNull(startIndex + 1) ?: return null

        var format = ReportFormat.TEXT
        var outputFile: Path? = null
        var index = startIndex + 2

        while (index < args.size) {
            when (args[index]) {
                "--format" -> {
                    val value = args.getOrNull(index + 1) ?: return null
                    format = when (value.lowercase()) {
                        "text" -> ReportFormat.TEXT
                        "json" -> ReportFormat.JSON
                        else -> return null
                    }
                    index += 2
                }
                "--report" -> {
                    val value = args.getOrNull(index + 1) ?: return null
                    outputFile = Path.of(value)
                    index += 2
                }
                else -> return null
            }
        }

        return ParsedArgs(
            contractsDirectory = Path.of(contractsDirArg),
            format = format,
            outputFile = outputFile,
        )
    }

    private data class ParsedArgs(
        val contractsDirectory: Path,
        val format: ReportFormat,
        val outputFile: Path?,
    )

    private enum class ReportFormat {
        TEXT,
        JSON,
    }
}
