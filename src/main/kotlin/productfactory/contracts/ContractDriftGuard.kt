package productfactory.contracts

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Path
import kotlin.io.path.writeText

data class ContractDriftIssue(
    val field: String,
    val schema: String,
    val kind: ContractValidationErrorKind,
    val message: String,
    val value: String?,
)

data class ContractDriftEntry(
    val contractFile: String,
    val schemaFile: String,
    val compatible: Boolean,
    val issues: List<ContractDriftIssue>,
)

data class ContractDriftReport(
    val contractsDirectory: String,
    val compatible: Boolean,
    val totalContracts: Int,
    val incompatibleContracts: Int,
    val totalIssues: Int,
    val entries: List<ContractDriftEntry>,
)

object ContractDriftGuard {
    private val jsonMapper = ObjectMapper().registerKotlinModule()

    fun buildReport(
        contractsDirectory: Path,
        validationResult: ContractValidationResult,
    ): ContractDriftReport {
        val issuesByContract = validationResult.errors.groupBy { it.contractFile }
        val entries = ContractType.entries.map { contractType ->
            val issues = issuesByContract[contractType.yamlFileName].orEmpty()
                .map { error ->
                    ContractDriftIssue(
                        field = error.field,
                        schema = error.schema,
                        kind = error.kind,
                        message = error.message,
                        value = error.value,
                    )
                }
            ContractDriftEntry(
                contractFile = contractType.yamlFileName,
                schemaFile = contractType.schemaFileName,
                compatible = issues.isEmpty(),
                issues = issues,
            )
        }
        val incompatibleContracts = entries.count { !it.compatible }
        return ContractDriftReport(
            contractsDirectory = contractsDirectory.toAbsolutePath().toString(),
            compatible = incompatibleContracts == 0,
            totalContracts = entries.size,
            incompatibleContracts = incompatibleContracts,
            totalIssues = validationResult.errors.size,
            entries = entries,
        )
    }

    fun renderText(report: ContractDriftReport): String {
        val lines = mutableListOf<String>()
        lines += "Contract Drift Guard report"
        lines += "contractsDirectory=${report.contractsDirectory}"
        lines += "compatible=${report.compatible} totalContracts=${report.totalContracts} incompatibleContracts=${report.incompatibleContracts} totalIssues=${report.totalIssues}"
        for (entry in report.entries) {
            lines += "- contract=${entry.contractFile} schema=${entry.schemaFile} compatible=${entry.compatible} issues=${entry.issues.size}"
            for (issue in entry.issues) {
                val valuePart = issue.value?.let { " value=$it" } ?: ""
                lines += "  kind=${issue.kind} field=${issue.field} schema=${issue.schema} message=${issue.message}$valuePart"
            }
        }
        return lines.joinToString(separator = System.lineSeparator())
    }

    fun renderJson(report: ContractDriftReport): String = jsonMapper
        .writerWithDefaultPrettyPrinter()
        .writeValueAsString(report)

    fun writeReport(outputPath: Path, content: String) {
        outputPath.parent?.let { parent ->
            java.nio.file.Files.createDirectories(parent)
        }
        outputPath.writeText(content)
    }
}
