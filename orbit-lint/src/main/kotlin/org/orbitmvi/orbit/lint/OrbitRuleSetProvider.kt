package org.orbitmvi.orbit.lint

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

class OrbitRuleSetProvider : RuleSetProvider {

    override val ruleSetId: String = "orbit"

    override fun instance(config: Config): RuleSet = RuleSet(
        ruleSetId,
        listOf(
            UseOrbitLaunchRule(config),
            UseOrbitLaunchOnSubscriptionRule(config)
        )
    )
}
