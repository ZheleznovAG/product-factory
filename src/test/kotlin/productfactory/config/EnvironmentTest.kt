package productfactory.config

import kotlin.test.Test
import kotlin.test.assertEquals

class EnvironmentTest {

    @Test
    fun parse_null_or_blank_returns_local() {
        assertEquals(FactoryEnvironment.LOCAL, FactoryEnvironment.parse(null))
        assertEquals(FactoryEnvironment.LOCAL, FactoryEnvironment.parse(""))
        assertEquals(FactoryEnvironment.LOCAL, FactoryEnvironment.parse("   "))
    }

    @Test
    fun parse_local_ci_k8s_case_insensitive() {
        assertEquals(FactoryEnvironment.LOCAL, FactoryEnvironment.parse("local"))
        assertEquals(FactoryEnvironment.LOCAL, FactoryEnvironment.parse("LOCAL"))
        assertEquals(FactoryEnvironment.CI, FactoryEnvironment.parse("ci"))
        assertEquals(FactoryEnvironment.CI, FactoryEnvironment.parse("CI"))
        assertEquals(FactoryEnvironment.K8S, FactoryEnvironment.parse("k8s"))
        assertEquals(FactoryEnvironment.K8S, FactoryEnvironment.parse("K8S"))
    }

    @Test
    fun parse_unknown_returns_local() {
        assertEquals(FactoryEnvironment.LOCAL, FactoryEnvironment.parse("prod"))
        assertEquals(FactoryEnvironment.LOCAL, FactoryEnvironment.parse("staging"))
    }

    @Test
    fun envName_matches() {
        assertEquals("local", FactoryEnvironment.LOCAL.envName)
        assertEquals("ci", FactoryEnvironment.CI.envName)
        assertEquals("k8s", FactoryEnvironment.K8S.envName)
    }
}
