package productfactory.contracts

import java.nio.file.Path

object ContractValidationCli {
    private const val usage = "Usage: product-factory validate <contracts-dir> | product-factory pf validate <contracts-dir>"

    fun run(args: Array<String>): Int {
        val contractsDirArg = when {
            args.size == 2 && args[0] == "validate" -> args[1]
            args.size == 3 && args[0] == "pf" && args[1] == "validate" -> args[2]
            else -> null
        }
        if (contractsDirArg == null) {
            System.err.println(usage)
            return 2
        }

        val contractsDirectory = Path.of(contractsDirArg)
        val validator = ContractValidator(schemaDirectory = Path.of("contracts", "schemas"))
        val result = validator.validateDirectory(contractsDirectory)

        if (result.valid) {
            println("Contracts are valid: ${contractsDirectory.toAbsolutePath()}")
            return 0
        }

        println("Contract validation failed (${result.errors.size} errors):")
        for (error in result.errors) {
            val valuePart = error.value?.let { " value=$it" } ?: ""
            println(
                "contract=${error.contractFile} kind=${error.kind} field=${error.field} schema=${error.schema} message=${error.message}$valuePart",
            )
        }
        return 1
    }
}
