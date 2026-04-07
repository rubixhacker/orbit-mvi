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

class UseOrbitLaunchRule(config: Config) : Rule(config) {

    override val issue = Issue(
        id = "UseOrbitLaunch",
        severity = Severity.Warning,
        description = "Use Syntax.launch instead of coroutineScope { launch { } } in Orbit intents.",
        debt = Debt.FIVE_MINS
    )

    @Suppress("ReturnCount")
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        val callee = (expression.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
        if (callee != "coroutineScope") return

        val lambda = expression.lambdaArguments.firstOrNull() ?: return
        val lambdaBody = lambda.getLambdaExpression()?.bodyExpression ?: return

        val hasLaunchCall = lambdaBody.statements.any { statement ->
            val call = statement as? KtCallExpression ?: return@any false
            val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
            name == "launch"
        }

        if (hasLaunchCall) {
            report(
                CodeSmell(
                    issue,
                    Entity.from(expression),
                    "Replace coroutineScope { launch { } } with Syntax.launch { } in Orbit intents."
                )
            )
        }
    }
}
