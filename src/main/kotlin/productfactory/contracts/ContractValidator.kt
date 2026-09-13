package productfactory.contracts

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

class ContractValidator(
    schemaDirectory: Path,
) {
    private val schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    private val yamlMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
    private val schemas: Map<ContractType, JsonSchema> = ContractType.entries.associateWith { contractType ->
        val schemaPath = schemaDirectory.resolve(contractType.schemaFileName)
        require(schemaPath.exists() && schemaPath.isRegularFile()) {
            "Schema file not found: $schemaPath"
        }
        schemaFactory.getSchema(schemaPath.toUri())
    }

    fun validateDirectory(contractsDirectory: Path): ContractValidationResult {
        val errors = mutableListOf<ContractValidationError>()

        for (contractType in ContractType.entries) {
            val contractPath = contractsDirectory.resolve(contractType.yamlFileName)
            if (!contractPath.exists() || !contractPath.isRegularFile()) {
                errors += ContractValidationError(
                    contractFile = contractType.yamlFileName,
                    field = "$",
                    schema = contractType.schemaFileName,
                    kind = ContractValidationErrorKind.MISSING_FILE,
                    message = "Contract file not found",
                    value = null,
                )
                continue
            }

            val document = try {
                yamlMapper.readTree(contractPath.toFile())
            } catch (ex: Exception) {
                errors += ContractValidationError(
                    contractFile = contractType.yamlFileName,
                    field = "$",
                    schema = contractType.schemaFileName,
                    kind = ContractValidationErrorKind.INVALID_YAML,
                    message = "Invalid YAML: ${ex.message ?: ex.javaClass.simpleName}",
                    value = null,
                )
                continue
            }

            if (document == null || document.isNull) {
                errors += ContractValidationError(
                    contractFile = contractType.yamlFileName,
                    field = "$",
                    schema = contractType.schemaFileName,
                    kind = ContractValidationErrorKind.EMPTY_DOCUMENT,
                    message = "YAML document is empty",
                    value = null,
                )
                continue
            }

            val validationMessages = schemas.getValue(contractType).validate(document)
            for (msg in validationMessages) {
                val fieldPath = (msg.getInstanceLocation()?.toString() ?: msg.getEvaluationPath()?.toString())?.takeIf { it.isNotBlank() } ?: "$"
                val schemaLoc = msg.getSchemaLocation()?.toString() ?: contractType.schemaFileName
                val msgText = msg.getMessage()
                errors += ContractValidationError(
                    contractFile = contractType.yamlFileName,
                    field = fieldPath,
                    schema = schemaLoc,
                    kind = ContractValidationErrorKind.SCHEMA_VIOLATION,
                    message = msgText,
                    value = extractValue(document, fieldPath),
                )
            }
        }

        val sortedErrors = errors.sortedWith(
            compareBy(
                ContractValidationError::contractFile,
                ContractValidationError::field,
                ContractValidationError::schema,
                ContractValidationError::message,
            ),
        )

        return ContractValidationResult(
            valid = sortedErrors.isEmpty(),
            errors = sortedErrors,
        )
    }

    private fun extractValue(document: JsonNode, fieldPath: String): String? {
        if (!fieldPath.startsWith("$")) {
            return null
        }
        val pointer = toJsonPointer(fieldPath)
        if (pointer.isEmpty()) {
            return document.toString()
        }
        val node = document.at(pointer)
        if (node.isMissingNode || node.isNull) {
            return null
        }
        return node.toString()
    }

    private fun toJsonPointer(fieldPath: String): String {
        if (fieldPath == "$") {
            return ""
        }

        val trimmed = fieldPath.removePrefix("$").trimStart('.')
        if (trimmed.isEmpty()) {
            return ""
        }

        val tokens = mutableListOf<String>()
        var index = 0

        while (index < trimmed.length) {
            when (trimmed[index]) {
                '.' -> index += 1
                '[' -> {
                    val end = trimmed.indexOf(']', startIndex = index)
                    if (end <= index + 1) return ""
                    tokens += trimmed.substring(index + 1, end)
                    index = end + 1
                }
                else -> {
                    var end = index
                    while (end < trimmed.length && trimmed[end] != '.' && trimmed[end] != '[') {
                        end += 1
                    }
                    tokens += trimmed.substring(index, end)
                    index = end
                }
            }
        }

        return tokens.joinToString(separator = "", prefix = "/") { token ->
            token.replace("~", "~0").replace("/", "~1")
        }
    }
}
