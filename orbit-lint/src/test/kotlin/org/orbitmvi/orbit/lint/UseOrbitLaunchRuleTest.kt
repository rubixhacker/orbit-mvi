package org.orbitmvi.orbit.lint

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.compileAndLint
import kotlin.test.Test
import kotlin.test.assertEquals

class UseOrbitLaunchRuleTest {

    private val rule = UseOrbitLaunchRule(Config.empty)

    @Test
    fun `reports coroutineScope launch pattern`() {
        val code = """
            import kotlinx.coroutines.coroutineScope
            import kotlinx.coroutines.launch

            suspend fun test() {
                coroutineScope {
                    launch {
                        doSomething()
                    }
                }
            }
        """.trimIndent()

        val findings = rule.compileAndLint(code)
        assertEquals(1, findings.size)
        assertEquals("UseOrbitLaunch", findings.first().id)
    }

    @Test
    fun `does not report standalone coroutineScope without launch`() {
        val code = """
            import kotlinx.coroutines.coroutineScope

            suspend fun test() {
                coroutineScope {
                    doSomething()
                }
            }
        """.trimIndent()

        val findings = rule.compileAndLint(code)
        assertEquals(0, findings.size)
    }
}
