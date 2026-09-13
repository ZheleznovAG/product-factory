package productfactory.workflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GateVerdictTest {

    @Test
    fun testRunResult_skipped_producesWarn() {
        val result = TestRunResult(skipped = true, passed = true, output = "no Gradle build file")
        val v = result.toVerdict()
        assertEquals(VerdictStatus.WARN, v.status)
        assertEquals(VerdictReason.TESTS_SKIPPED, v.reasonCode)
        assertTrue(v.isPassOrWarn())
        assertFalse(v.isFailure())
    }

    @Test
    fun testRunResult_passed_producesPass() {
        val result = TestRunResult(skipped = false, passed = true, exitCode = 0)
        val v = result.toVerdict()
        assertEquals(VerdictStatus.PASS, v.status)
        assertEquals(VerdictReason.TESTS_PASSED, v.reasonCode)
        assertTrue(v.isPassOrWarn())
        assertFalse(v.isFailure())
    }

    @Test
    fun testRunResult_failed_producesFail() {
        val result = TestRunResult(skipped = false, passed = false, exitCode = 1, error = "test failure")
        val v = result.toVerdict()
        assertEquals(VerdictStatus.FAIL, v.status)
        assertEquals(VerdictReason.TESTS_FAILED, v.reasonCode)
        assertFalse(v.isPassOrWarn())
        assertTrue(v.isFailure())
    }

    @Test
    fun securityRunResult_skipped_producesWarn() {
        val result = SecurityRunResult(skipped = true, passed = true, output = "tools unavailable")
        val v = result.toVerdict()
        assertEquals(VerdictStatus.WARN, v.status)
        assertEquals(VerdictReason.SECURITY_SKIPPED, v.reasonCode)
    }

    @Test
    fun securityRunResult_passed_producesPass() {
        val result = SecurityRunResult(skipped = false, passed = true)
        val v = result.toVerdict()
        assertEquals(VerdictStatus.PASS, v.status)
        assertEquals(VerdictReason.SECURITY_PASSED, v.reasonCode)
    }

    @Test
    fun securityRunResult_failed_producesFail() {
        val result = SecurityRunResult(skipped = false, passed = false, gitleaksExitCode = 1, error = "secret found")
        val v = result.toVerdict()
        assertEquals(VerdictStatus.FAIL, v.status)
        assertEquals(VerdictReason.SECURITY_FAILED, v.reasonCode)
        assertTrue(v.isFailure())
    }
}
