package org.orbitmvi.orbit.lint

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

class UseOrbitLaunchOnSubscriptionRule(config: Config) : Rule(config) {

    override val issue = Issue(
        id = "UseOrbitLaunchOnSubscription",
        severity = Severity.Warning,
        description = "Use launchOnSubscription instead of the deprecated repeatOnSubscription.",
        debt = Debt.FIVE_MINS
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        val callee = (expression.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
        if (callee == "repeatOnSubscription") {
            report(
                CodeSmell(
                    issue,
                    Entity.from(expression),
                    "Replace repeatOnSubscription with launchOnSubscription."
                )
            )
        }
    }
}
