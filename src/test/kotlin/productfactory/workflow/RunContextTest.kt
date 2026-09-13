package productfactory.workflow

import productfactory.agent.AgentPlannerArtifacts
import productfactory.agent.AdrDraftArtifact
import productfactory.agent.CoverageTargets
import productfactory.agent.PipelinePlanArtifact
import productfactory.agent.PipelinePlanStep
import productfactory.agent.TestCasePlan
import productfactory.agent.TestPlanArtifact
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RunContextTest {

    @Test
    fun `RunContext starts with runId only`() {
        val ctx = RunContext(runId = "run-1")
        assertEquals("run-1", ctx.runId)
        assertNull(ctx.plannerArtifacts)
        assertNull(ctx.codegenArtifact)
        assertNull(ctx.qaOutput)
        assertNull(ctx.secOutput)
    }

    @Test
    fun `RunContext withPlannerArtifacts and withQaOutput`() {
        val plan = PipelinePlanArtifact(steps = listOf(PipelinePlanStep("s1", "desc", "tool", emptyList())))
        val adr = AdrDraftArtifact(context = "c", decision = "d", consequences = listOf("x"))
        val testPlan = TestPlanArtifact(
            scope = "unit",
            cases = listOf(TestCasePlan("t1", "desc", "unit")),
            coverage_targets = CoverageTargets(unit_percent = 80, integration_required = false, security_required = true),
        )
        val artifacts = AgentPlannerArtifacts(pipelinePlan = plan, adrDraft = adr, testPlan = testPlan)
        var ctx = RunContext(runId = "run-2")
        ctx = ctx.withPlannerArtifacts(artifacts)
        assertEquals(artifacts, ctx.plannerArtifacts)

        val verdict = GateVerdict(VerdictStatus.PASS, VerdictReason.TESTS_PASSED, "ok")
        val qaOut = QaGateOutput(verdict, summary = "all passed")
        ctx = ctx.withQaOutput(qaOut)
        assertEquals(qaOut.toVerdict().status, VerdictStatus.PASS)
        assertEquals("all passed", ctx.qaOutput?.summary)
    }

    @Test
    fun `QaGateOutput and SecGateOutput roundtrip verdict`() {
        val v = GateVerdict(VerdictStatus.WARN, VerdictReason.TESTS_SKIPPED, "skipped")
        val qa = QaGateOutput(v)
        assertEquals(VerdictStatus.WARN, qa.toVerdict().status)
        assertEquals(VerdictReason.TESTS_SKIPPED, qa.toVerdict().reasonCode)

        val vs = GateVerdict(VerdictStatus.FAIL, VerdictReason.SECURITY_FAILED, "secret found")
        val sec = SecGateOutput(vs)
        assertEquals(VerdictStatus.FAIL, sec.toVerdict().status)
    }
}
