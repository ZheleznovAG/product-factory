package productfactory.contracts

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContractDriftGuardCliTest {

    @Test
    fun `run returns usage error for unsupported args`() {
        val exitCode = ContractDriftGuardCli.run(arrayOf("bad"))
        assertEquals(2, exitCode)
    }

    @Test
    fun `run returns success for compatible contracts and writes json report`() {
        val contractsDir = createContractsFixture()
        val reportPath = contractsDir.resolve("reports").resolve("drift-report.json")

        val exitCode = ContractDriftGuardCli.run(
            arrayOf("contract-drift-guard", contractsDir.toString(), "--format", "json", "--report", reportPath.toString()),
        )

        assertEquals(0, exitCode)
        assertTrue(reportPath.exists())
        val reportContent = reportPath.readText()
        assertTrue(reportContent.contains("\"compatible\" : true"))
        assertTrue(reportContent.contains("\"incompatibleContracts\" : 0"))
    }

    @Test
    fun `run returns non-zero and reports missing contract file`() {
        val contractsDir = createContractsFixture()
        Files.delete(contractsDir.resolve("risk_profile.yaml"))
        val reportPath = contractsDir.resolve("drift-report.txt")

        val exitCode = ContractDriftGuardCli.run(
            arrayOf("contract-drift-guard", contractsDir.toString(), "--report", reportPath.toString()),
        )

        assertEquals(1, exitCode)
        assertTrue(reportPath.exists())
        val reportContent = reportPath.readText()
        assertTrue(reportContent.contains("compatible=false"))
        assertTrue(reportContent.contains("kind=MISSING_FILE"))
        assertTrue(reportContent.contains("contract=risk_profile.yaml"))
    }

    private fun createContractsFixture(): Path {
        val tempDir = Files.createTempDirectory("contract-drift-guard-test")
        val fixtureDir = tempDir.resolve("contracts").createDirectories()
        for (contract in ContractType.entries) {
            Files.copy(
                Path.of("contracts-example").resolve(contract.yamlFileName),
                fixtureDir.resolve(contract.yamlFileName),
            )
        }
        return fixtureDir
    }
}
