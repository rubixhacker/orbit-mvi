package org.orbitmvi.orbit.lint

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.compileAndLint
import kotlin.test.Test
import kotlin.test.assertEquals

class UseOrbitLaunchOnSubscriptionRuleTest {

    private val rule = UseOrbitLaunchOnSubscriptionRule(Config.empty)

    @Test
    fun `reports repeatOnSubscription usage`() {
        val code = """
            fun test() {
                repeatOnSubscription {
                    doSomething()
                }
            }
        """.trimIndent()

        val findings = rule.compileAndLint(code)
        assertEquals(1, findings.size)
        assertEquals("UseOrbitLaunchOnSubscription", findings.first().id)
    }

    @Test
    fun `does not report launchOnSubscription usage`() {
        val code = """
            fun test() {
                launchOnSubscription {
                    doSomething()
                }
            }
        """.trimIndent()

        val findings = rule.compileAndLint(code)
        assertEquals(0, findings.size)
    }
}
