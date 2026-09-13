package productfactory.contracts

import kotlin.test.Test
import kotlin.test.assertEquals

class ContractValidationCliTest {

    @Test
    fun `run returns usage error for unsupported args`() {
        val exitCode = ContractValidationCli.run(arrayOf("bad"))
        assertEquals(2, exitCode)
    }
}
