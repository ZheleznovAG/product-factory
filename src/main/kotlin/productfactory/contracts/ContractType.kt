package productfactory.contracts

/**
 * Mapping between expected input contract files and JSON Schemas.
 */
enum class ContractType(
    val yamlFileName: String,
    val schemaFileName: String,
) {
    PRODUCT("product.yaml", "product.schema.json"),
    CONSTRAINTS("constraints.yaml", "constraints.schema.json"),
    QUALITY_PROFILE("quality_profile.yaml", "quality_profile.schema.json"),
    RISK_PROFILE("risk_profile.yaml", "risk_profile.schema.json"),
    TARGET_STACK("target_stack.yaml", "target_stack.schema.json"),
}
