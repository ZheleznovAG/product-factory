package productfactory.contracts

/**
 * Deterministic validation error for one contract field.
 */
data class ContractValidationError(
    val contractFile: String,
    val field: String,
    val schema: String,
    val kind: ContractValidationErrorKind = ContractValidationErrorKind.SCHEMA_VIOLATION,
    val message: String,
    val value: String?,
)

enum class ContractValidationErrorKind {
    MISSING_FILE,
    INVALID_YAML,
    EMPTY_DOCUMENT,
    SCHEMA_VIOLATION,
}

/**
 * Validation summary for a contract directory.
 */
data class ContractValidationResult(
    val valid: Boolean,
    val errors: List<ContractValidationError>,
)
