# Flow Collection API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `launch` and `launchOnSubscription` methods to `Syntax`, deprecate `repeatOnSubscription`, and add detekt lint rules to catch outdated patterns.

**Architecture:** Thread `CoroutineScope` through `ContainerContext` so `Syntax` can create structured child coroutines. New methods on `Syntax` replace the verbose `coroutineScope { launch { subIntent { } } }` pattern. Detekt custom rules flag the old patterns.

**Tech Stack:** Kotlin Multiplatform, kotlinx.coroutines, Detekt 1.23.8, Turbine (tests)

---

### Task 1: Thread CoroutineScope through ContainerContext

**Files:**
- Modify: `orbit-core/src/commonMain/kotlin/org/orbitmvi/orbit/syntax/ContainerContext.kt`
- Modify: `orbit-core/src/commonMain/kotlin/org/orbitmvi/orbit/syntax/SubStateContainerContext.kt`
- Modify: `orbit-core/src/commonMain/kotlin/org/orbitmvi/orbit/syntax/RunOn.kt:70-85`
- Modify: `orbit-core/src/commonMain/kotlin/org/orbitmvi/orbit/internal/RealContainer.kt:94-113`
- Modify: `orbit-core/src/commonTest/kotlin/org/orbitmvi/orbit/syntax/simple/SimpleDslRepeatOnSubscriptionTest.kt:48-55`

- [ ] **Step 1: Add `scope` to `ContainerContext`**

In `orbit-core/src/commonMain/kotlin/org/orbitmvi/orbit/syntax/ContainerContext.kt`, add the import and field:

```kotlin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.orbitmvi.orbit.RealSettings
import org.orbitmvi.orbit.annotation.OrbitInternal
import org.orbitmvi.orbit.internal.repeatonsubscription.SubscribedCounter

@OrbitInternal
public data class ContainerContext<S : Any, SE : Any>(
    public val settings: RealSettings,
    public val postSideEffect: suspend (SE) -> Unit,
    public val reduce: suspend ((S) -> S) -> Unit,
    public val subscribedCounter: SubscribedCounter,
    public val stateFlow: StateFlow<S>,
    public val scope: CoroutineScope,
) {
    public val state: S
        get() = stateFlow.value
}
```

- [ ] **Step 2: Add `scope` to `SubStateContainerContext`**

In `orbit-core/src/commonMain/kotlin/org/orbitmvi/orbit/syntax/SubStateContainerContext.kt`, add the import and field:

```kotlin
import kotlinx.coroutines.CoroutineScope
import org.orbitmvi.orbit.RealSettings
import org.orbitmvi.orbit.annotation.OrbitInternal
import org.orbitmvi.orbit.internal.repeatonsubscription.SubscribedCounter

@OrbitInternal
public data class SubStateContainerContext<S : Any, SE : Any, T : S>(
    public val settings: RealSettings,
    public val postSideEffect: suspend (SE) -> Unit,
    private val getState: () -> T,
    public val reduce: suspend ((T) -> S) -> Unit,
    public val subscribedCounter: SubscribedCounter,
    public val scope: CoroutineScope,
) {
    public val state: T
        get() = getState()
}
```

- [ ] **Step 3: Propagate scope in `toSubclassContainerContext`**

In `orbit-core/src/commonMain/kotlin/org/orbitmvi/orbit/syntax/RunOn.kt`, update the function at line 70 to pass scope through:

```kotlin
@OrbitInternal
public inline fun <S : Any, SE : Any, reified T : S> ContainerContext<S, SE>.toSubclassContainerContext(
    crossinline predicate: (T) -> Boolean = { true },
    capturedState: T,
): SubStateContainerContext<S, SE, T> {
    return SubStateContainerContext(
        settings = settings,
        postSideEffect = postSideEffect,
        reduce = { reducer ->
            reduce { state ->
                (state as? T)?.takeIf(predicate)?.let { reducer(it) } ?: state
            }
        },
        subscribedCounter = subscribedCounter,
        getState = { capturedState },
        scope = scope
    )
}
```

- [ ] **Step 4: Update `RealContainer` to pass scope per intent**

In `orbit-core/src/commonMain/kotlin/org/orbitmvi/orbit/internal/RealContainer.kt`:

First, update `pluginContext` (line 94) to use the container's scope as default:

```kotlin
internal val pluginContext: ContainerContext<INTERNAL_STATE, SIDE_EFFECT> = ContainerContext(
    settings = settings,
    postSideEffect = { sideEffectChannel.send(it) },
    reduce = { reducer -> internalStateFlow.update(reducer) },
    subscribedCounter = subscribedCounter,
    stateFlow = stateFlow,
    scope = scope,
)
```

Then update `inlineOrbit` (line 110) to wrap in `coroutineScope` and pass the scope:

```kotlin
override suspend fun inlineOrbit(orbitIntent: suspend ContainerContext<INTERNAL_STATE, SIDE_EFFECT>.() -> Unit) {
    initialiseIfNeeded()
    coroutineScope {
        pluginContext.copy(scope = this).orbitIntent()
    }
}
```

Add `import kotlinx.coroutines.coroutineScope` to the imports if not already present.

Then update `initialiseIfNeeded` (line 131) to pass the intent's coroutine scope:

```kotlin
launch(exceptionHandlerContext) {
    val scopedContext = pluginContext.copy(scope = this)
    runCatching { scopedContext.intent() }.onFailure { e ->
        settings.exceptionHandler?.handleException(coroutineContext, e) ?: throw e
    }
}.invokeOnCompletion { job.complete() }
```

- [ ] **Step 5: Fix test that creates ContainerContext directly**

In `orbit-core/src/commonTest/kotlin/org/orbitmvi/orbit/syntax/simple/SimpleDslRepeatOnSubscriptionTest.kt`, update lines 48-56 to pass the scope:

```kotlin
private val syntax = Syntax(
    containerContext = ContainerContext<Unit, Unit>(
        settings = RealSettings(),
        postSideEffect = {},
        reduce = {},
        subscribedCounter = testSubscribedCounter,
        stateFlow = MutableStateFlow(Unit),
        scope = testScope
    )
)
```

- [ ] **Step 6: Run existing tests to verify no regressions**

Run: `./gradlew :orbit-core:allTests`
Expected: All existing tests pass. The scope threading is purely additive — no behavior changes.

- [ ] **Step 7: Commit**

```bash
git add orbit-core/src/commonMain/kotlin/org/orbitmvi/orbit/syntax/ContainerContext.kt \
       orbit-core/src/commonMain/kotlin/org/orbitmvi/orbit/syntax/SubStateContainerContext.kt \
       orbit-core/src/commonMain/kotlin/org/orbitmvi/orbit/syntax/RunOn.kt \
       orbit-core/src/commonMain/kotlin/org/orbitmvi/orbit/internal/RealContainer.kt \
       orbit-core/src/commonTest/kotlin/org/orbitmvi/orbit/syntax/simple/SimpleDslRepeatOnSubscriptionTest.kt
git commit -m "feat: thread CoroutineScope through ContainerContext (#281)"
```

---

### Task 2: Add `Syntax.launch` (TDD)

**Files:**
- Create: `orbit-core/src/commonTest/kotlin/org/orbitmvi/orbit/internal/LaunchTest.kt`
- Modify: `orbit-core/src/commonMain/kotlin/org/orbitmvi/orbit/syntax/Syntax.kt`

- [ ] **Step 1: Write failing test for `Syntax.launch`**

Create `orbit-core/src/commonTest/kotlin/org/orbitmvi/orbit/internal/LaunchTest.kt`:

```kotlin
/*
 * Copyright 2025 Mikołaj Leszczyński & Appmattus Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.orbitmvi.orbit.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.test.runTest
import org.orbitmvi.orbit.OrbitContainerHost
import org.orbitmvi.orbit.orbitContainer
import org.orbitmvi.orbit.test.testWithInternalState
import kotlin.random.Random
import kotlin.test.Test

internal class LaunchTest {

    @Test
    fun multiple_launches_can_collect_flows_in_parallel() = runTest {
        val initialState = TestState()
        val channel1 = Channel<String>()
        val channel2 = Channel<String>()
        LaunchMiddleware(
            scope = backgroundScope,
            initialState = initialState,
            flow1 = channel1.consumeAsFlow(),
            flow2 = channel2.consumeAsFlow()
        ).testWithInternalState(this) {
            val job = runOnCreate()

            val str1 = Random.nextInt().toString()
            channel1.send(str1)
            expectSideEffect(str1)

            val str2 = Random.nextInt().toString()
            channel2.send(str2)
            expectSideEffect(str2)

            job.cancel()
        }
    }

    @Test
    fun launch_can_reduce_state() = runTest {
        val initialState = TestState()
        val channel = Channel<Int>()
        LaunchReduceMiddleware(
            scope = backgroundScope,
            initialState = initialState,
            flow = channel.consumeAsFlow()
        ).testWithInternalState(this) {
            val job = runOnCreate()

            channel.send(42)
            expectInternalState { initialState.copy(id = 42) }

            channel.send(99)
            expectInternalState { initialState.copy(id = 99) }

            job.cancel()
        }
    }

    private data class TestState(val id: Int = Random.nextInt())

    private class LaunchMiddleware(
        scope: CoroutineScope,
        initialState: TestState,
        private val flow1: Flow<String>,
        private val flow2: Flow<String>
    ) : OrbitContainerHost<TestState, TestState, String> {
        override val container = scope.orbitContainer<TestState, String>(initialState) {
            launch {
                flow1.collect {
                    postSideEffect(it)
                }
            }
            launch {
                flow2.collect {
                    postSideEffect(it)
                }
            }
        }
    }

    private class LaunchReduceMiddleware(
        scope: CoroutineScope,
        initialState: TestState,
        private val flow: Flow<Int>
    ) : OrbitContainerHost<TestState, TestState, Nothing> {
        override val container = scope.orbitContainer<TestState, Nothing>(initialState) {
            launch {
                flow.collect { value ->
                    reduce { state.copy(id = value) }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :orbit-core:allTests --tests "org.orbitmvi.orbit.internal.LaunchTest"`
Expected: Compilation error — `launch` is not defined on `Syntax`.

- [ ] **Step 3: Implement `Syntax.launch`**

In `orbit-core/src/commonMain/kotlin/org/orbitmvi/orbit/syntax/Syntax.kt`, add the `launch` method after `postSideEffect` (after line 64):

```kotlin
    /**
     * Launches a new coroutine as a child of the current intent's scope.
     *
     * This is useful for collecting multiple flows concurrently within an intent or [onCreate][org.orbitmvi.orbit.orbitContainer].
     * Each launched coroutine runs concurrently, and the parent intent stays alive while children run.
     * Children are cancelled when the container is cancelled.
     *
     * ```
     * override val container = scope.orbitContainer<State, SideEffect>(initialState) {
     *     launch {
     *         flow1.collect { value -> reduce { state.copy(field1 = value) } }
     *     }
     *     launch {
     *         flow2.collect { value -> reduce { state.copy(field2 = value) } }
     *     }
     * }
     * ```
     *
     * @param block the lambda to execute in the launched coroutine, with access to the orbit DSL.
     */
    @OrbitDsl
    public fun launch(block: suspend Syntax<S, SE>.() -> Unit) {
        containerContext.scope.launch {
            Syntax(containerContext).block()
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :orbit-core:allTests --tests "org.orbitmvi.orbit.internal.LaunchTest"`
Expected: PASS — both tests green.

- [ ] **Step 5: Commit**

```bash
git add orbit-core/src/commonTest/kotlin/org/orbitmvi/orbit/internal/LaunchTest.kt \
       orbit-core/src/commonMain/kotlin/org/orbitmvi/orbit/syntax/Syntax.kt
git commit -m "feat: add Syntax.launch for concurrent flow collection (#281)"
```

---

### Task 3: Add `Syntax.launchOnSubscription` (TDD)

**Files:**
- Create: `orbit-core/src/commonTest/kotlin/org/orbitmvi/orbit/internal/LaunchOnSubscriptionTest.kt`
- Modify: `orbit-core/src/commonMain/kotlin/org/orbitmvi/orbit/syntax/Syntax.kt`

- [ ] **Step 1: Write failing test for `Syntax.launchOnSubscription`**

Create `orbit-core/src/commonTest/kotlin/org/orbitmvi/orbit/internal/LaunchOnSubscriptionTest.kt`:

```kotlin
/*
 * Copyright 2025 Mikołaj Leszczyński & Appmattus Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.orbitmvi.orbit.internal

import app.cash.turbine.test
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.orbitmvi.orbit.OrbitContainerHost
import org.orbitmvi.orbit.orbitContainer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.time.Duration.Companion.milliseconds

internal class LaunchOnSubscriptionTest {
    private val initialState = TestState()

    @Test
    fun block_is_not_executed_without_ref_count_subscription() = runTest {
        val testSubject = TestMiddleware(this)

        testSubject.container.stateFlow.test(timeout = 500.milliseconds) {
            assertEquals(initialState, awaitItem())

            val intentJob = testSubject.updateState { 42 }

            assertFails { awaitItem() }
            intentJob.cancel()
        }
    }

    @Test
    fun block_is_executed_with_ref_count_state_subscription() = runTest {
        val testSubject = TestMiddleware(this)

        testSubject.container.refCountStateFlow.test {
            assertEquals(initialState, awaitItem())

            val intentJob = testSubject.updateState { 42 }

            assertEquals(TestState(42), awaitItem())
            intentJob.cancel()
        }
    }

    @Test
    fun block_is_not_executed_without_ref_count_side_effect_subscription() = runTest {
        val testSubject = TestMiddleware(this)

        testSubject.container.sideEffectFlow.test(timeout = 500.milliseconds) {
            val intentJob = testSubject.updateSideEffect { 42 }

            assertFails { awaitItem() }
            intentJob.cancel()
        }
    }

    @Test
    fun block_is_executed_with_ref_count_side_effect_subscription() = runTest {
        val testSubject = TestMiddleware(this)

        testSubject.container.refCountSideEffectFlow.test {
            val intentJob = testSubject.updateSideEffect { 42 }

            assertEquals(42, awaitItem())
            intentJob.cancel()
        }
    }

    private inner class TestMiddleware(testScope: TestScope) : OrbitContainerHost<TestState, TestState, Int> {
        override val container = testScope.backgroundScope.orbitContainer<TestState, Int>(initialState)

        fun updateState(externalCall: suspend () -> Int) = intent {
            launchOnSubscription {
                val result = externalCall()
                reduce { TestState(result) }
            }
        }

        fun updateSideEffect(externalCall: suspend () -> Int) = intent {
            launchOnSubscription {
                val result = externalCall()
                postSideEffect(result)
            }
        }
    }

    private data class TestState(val count: Int = Random.nextInt())
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :orbit-core:allTests --tests "org.orbitmvi.orbit.internal.LaunchOnSubscriptionTest"`
Expected: Compilation error — `launchOnSubscription` is not defined on `Syntax`.

- [ ] **Step 3: Implement `Syntax.launchOnSubscription`**

In `orbit-core/src/commonMain/kotlin/org/orbitmvi/orbit/syntax/Syntax.kt`, add the `launchOnSubscription` method after the `launch` method added in Task 2:

```kotlin
    /**
     * Launches a new subscriber-aware coroutine as a child of the current intent's scope.
     *
     * The block runs when [OrbitContainer.refCountStateFlow] or [OrbitContainer.refCountSideEffectFlow]
     * have active subscribers, and is cancelled when subscribers reach zero (with debounce via
     * [SettingsBuilder.repeatOnSubscribedStopTimeout]).
     *
     * This is useful for collecting hot flows that should only run while the UI is subscribed.
     *
     * ```
     * override val container = scope.orbitContainer<State, SideEffect>(initialState) {
     *     launchOnSubscription {
     *         hotFlow.collect { value -> reduce { state.copy(data = value) } }
     *     }
     * }
     * ```
     *
     * @param block the lambda to execute when subscribers are active, with access to the orbit DSL.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @OrbitDsl
    public fun launchOnSubscription(block: suspend Syntax<S, SE>.() -> Unit) {
        containerContext.scope.launch {
            containerContext.subscribedCounter.subscribed.mapLatest {
                if (it.isSubscribed) Syntax(containerContext).block() else null
            }.collect()
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :orbit-core:allTests --tests "org.orbitmvi.orbit.internal.LaunchOnSubscriptionTest"`
Expected: PASS — all four tests green.

- [ ] **Step 5: Commit**

```bash
git add orbit-core/src/commonTest/kotlin/org/orbitmvi/orbit/internal/LaunchOnSubscriptionTest.kt \
       orbit-core/src/commonMain/kotlin/org/orbitmvi/orbit/syntax/Syntax.kt
git commit -m "feat: add Syntax.launchOnSubscription for subscriber-aware flow collection (#281)"
```

---

### Task 4: Deprecate `repeatOnSubscription` and add methods to `SubStateSyntax`

**Files:**
- Modify: `orbit-core/src/commonMain/kotlin/org/orbitmvi/orbit/syntax/Syntax.kt:78-90`
- Modify: `orbit-core/src/commonMain/kotlin/org/orbitmvi/orbit/syntax/SubStateSyntax.kt:73-85`

- [ ] **Step 1: Deprecate `repeatOnSubscription` on `Syntax`**

In `orbit-core/src/commonMain/kotlin/org/orbitmvi/orbit/syntax/Syntax.kt`, add the `@Deprecated` annotation to the existing `repeatOnSubscription` method (before the `@OptIn` annotation around line 78):

```kotlin
    @Deprecated(
        message = "Use launchOnSubscription instead. launchOnSubscription provides a Syntax receiver " +
            "and does not block the calling coroutine, allowing multiple concurrent launches.",
        replaceWith = ReplaceWith("launchOnSubscription { block() }")
    )
    @OptIn(ExperimentalCoroutinesApi::class)
    @OrbitDsl
    public suspend fun repeatOnSubscription(
        block: suspend CoroutineScope.() -> Unit
    ) {
```

(The method body stays the same.)

- [ ] **Step 2: Add `launch` and `launchOnSubscription` to `SubStateSyntax`**

In `orbit-core/src/commonMain/kotlin/org/orbitmvi/orbit/syntax/SubStateSyntax.kt`, add the two new methods and deprecate `repeatOnSubscription`. Add the `kotlinx.coroutines.Job` import as well.

After the `reduce` method (after line 59), add:

```kotlin
    /**
     * Launches a new coroutine as a child of the current intent's scope.
     *
     * @param block the lambda to execute in the launched coroutine, with access to the orbit DSL.
     */
    @OrbitDsl
    public fun launch(block: suspend SubStateSyntax<S, SE, T>.() -> Unit) {
        containerContext.scope.launch {
            SubStateSyntax(containerContext).block()
        }
    }

    /**
     * Launches a new subscriber-aware coroutine as a child of the current intent's scope.
     *
     * @param block the lambda to execute when subscribers are active, with access to the orbit DSL.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @OrbitDsl
    public fun launchOnSubscription(block: suspend SubStateSyntax<S, SE, T>.() -> Unit) {
        containerContext.scope.launch {
            containerContext.subscribedCounter.subscribed.mapLatest {
                if (it.isSubscribed) SubStateSyntax(containerContext).block() else null
            }.collect()
        }
    }
```

Then add the `@Deprecated` annotation to the existing `repeatOnSubscription` method (before line 73):

```kotlin
    @Deprecated(
        message = "Use launchOnSubscription instead. launchOnSubscription provides a SubStateSyntax receiver " +
            "and does not block the calling coroutine, allowing multiple concurrent launches.",
        replaceWith = ReplaceWith("launchOnSubscription { block() }")
    )
    @OptIn(ExperimentalCoroutinesApi::class)
    @OrbitDsl
    public suspend fun repeatOnSubscription(
```

- [ ] **Step 3: Run all tests to verify no regressions**

Run: `./gradlew :orbit-core:allTests`
Expected: All tests pass. Deprecation warnings may appear in existing tests that use `repeatOnSubscription` — this is expected.

- [ ] **Step 4: Commit**

```bash
git add orbit-core/src/commonMain/kotlin/org/orbitmvi/orbit/syntax/Syntax.kt \
       orbit-core/src/commonMain/kotlin/org/orbitmvi/orbit/syntax/SubStateSyntax.kt
git commit -m "feat: deprecate repeatOnSubscription in favor of launchOnSubscription (#281)"
```

---

### Task 5: Update samples to use new API

**Files:**
- Modify: `samples/orbit-stocklist-jetpack-compose/src/main/kotlin/org/orbitmvi/orbit/sample/stocklist/detail/business/DetailViewModel.kt:37-45`
- Modify: `samples/orbit-stocklist-jetpack-compose/src/main/kotlin/org/orbitmvi/orbit/sample/stocklist/list/business/ListViewModel.kt:35-43`

- [ ] **Step 1: Update `DetailViewModel`**

In `samples/orbit-stocklist-jetpack-compose/src/main/kotlin/org/orbitmvi/orbit/sample/stocklist/detail/business/DetailViewModel.kt`, replace the `requestStock()` method (around lines 37-45):

Before:
```kotlin
private fun requestStock() = intent(registerIdling = false) {
    repeatOnSubscription {
        stockRepository.stockDetails(itemName).collect {
            reduce {
                state.copy(stock = it)
            }
        }
    }
}
```

After:
```kotlin
private fun requestStock() = intent(registerIdling = false) {
    launchOnSubscription {
        stockRepository.stockDetails(itemName).collect {
            reduce {
                state.copy(stock = it)
            }
        }
    }
}
```

- [ ] **Step 2: Update `ListViewModel`**

In `samples/orbit-stocklist-jetpack-compose/src/main/kotlin/org/orbitmvi/orbit/sample/stocklist/list/business/ListViewModel.kt`, replace the `requestStocks()` method (around lines 35-43):

Before:
```kotlin
private fun requestStocks() = intent(registerIdling = false) {
    repeatOnSubscription {
        stockRepository.stockList().collect {
            reduce {
                state.copy(stocks = it)
            }
        }
    }
}
```

After:
```kotlin
private fun requestStocks() = intent(registerIdling = false) {
    launchOnSubscription {
        stockRepository.stockList().collect {
            reduce {
                state.copy(stocks = it)
            }
        }
    }
}
```

- [ ] **Step 3: Build samples to verify compilation**

Run: `./gradlew :samples:orbit-stocklist-jetpack-compose:assembleDebug`
Expected: Compilation success with deprecation warnings suppressed by migration.

- [ ] **Step 4: Commit**

```bash
git add samples/orbit-stocklist-jetpack-compose/src/main/kotlin/org/orbitmvi/orbit/sample/stocklist/detail/business/DetailViewModel.kt \
       samples/orbit-stocklist-jetpack-compose/src/main/kotlin/org/orbitmvi/orbit/sample/stocklist/list/business/ListViewModel.kt
git commit -m "refactor: migrate samples from repeatOnSubscription to launchOnSubscription (#281)"
```

---

### Task 6: Add detekt custom lint rules

**Files:**
- Modify: `settings.gradle.kts`
- Create: `orbit-lint/orbit-lint_build.gradle.kts`
- Create: `orbit-lint/src/main/kotlin/org/orbitmvi/orbit/lint/OrbitRuleSetProvider.kt`
- Create: `orbit-lint/src/main/kotlin/org/orbitmvi/orbit/lint/UseOrbitLaunchRule.kt`
- Create: `orbit-lint/src/main/kotlin/org/orbitmvi/orbit/lint/UseOrbitLaunchOnSubscriptionRule.kt`
- Create: `orbit-lint/src/main/resources/META-INF/services/io.gitlab.arturbosch.detekt.api.RuleSetProvider`
- Create: `orbit-lint/src/test/kotlin/org/orbitmvi/orbit/lint/UseOrbitLaunchRuleTest.kt`
- Create: `orbit-lint/src/test/kotlin/org/orbitmvi/orbit/lint/UseOrbitLaunchOnSubscriptionRuleTest.kt`
- Modify: `gradle/scripts/detekt.gradle.kts`

- [ ] **Step 1: Add `orbit-lint` to `settings.gradle.kts`**

In `settings.gradle.kts`, add `"orbit-lint"` to the `include` block:

```kotlin
include(
    "orbit-core",
    "orbit-test",
    "orbit-viewmodel",
    "orbit-compose",
    "orbit-lint",
    "samples:orbit-calculator",
    "samples:orbit-posts",
    "samples:orbit-posts-compose-multiplatform:composeApp",
    "samples:orbit-stocklist",
    "samples:orbit-stocklist-jetpack-compose",
    "samples:orbit-text",
    "test-common"
)
```

- [ ] **Step 2: Create `orbit-lint` build file**

Create `orbit-lint/orbit-lint_build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm")
}

dependencies {
    compileOnly("io.gitlab.arturbosch.detekt:detekt-api:1.23.8")
    testImplementation("io.gitlab.arturbosch.detekt:detekt-test:1.23.8")
    testImplementation(kotlin("test"))
}
```

- [ ] **Step 3: Write failing test for `UseOrbitLaunchRule`**

Create `orbit-lint/src/test/kotlin/org/orbitmvi/orbit/lint/UseOrbitLaunchRuleTest.kt`:

```kotlin
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
```

- [ ] **Step 4: Write failing test for `UseOrbitLaunchOnSubscriptionRule`**

Create `orbit-lint/src/test/kotlin/org/orbitmvi/orbit/lint/UseOrbitLaunchOnSubscriptionRuleTest.kt`:

```kotlin
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
```

- [ ] **Step 5: Run tests to verify they fail**

Run: `./gradlew :orbit-lint:test`
Expected: Compilation error — rule classes don't exist yet.

- [ ] **Step 6: Implement `UseOrbitLaunchRule`**

Create `orbit-lint/src/main/kotlin/org/orbitmvi/orbit/lint/UseOrbitLaunchRule.kt`:

```kotlin
package org.orbitmvi.orbit.lint

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

class UseOrbitLaunchRule(config: Config) : Rule(config) {

    override val issue = Issue(
        id = "UseOrbitLaunch",
        severity = Severity.Warning,
        description = "Use Syntax.launch instead of coroutineScope { launch { } } in Orbit intents.",
        debt = Debt.FIVE_MINS
    )

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
```

- [ ] **Step 7: Implement `UseOrbitLaunchOnSubscriptionRule`**

Create `orbit-lint/src/main/kotlin/org/orbitmvi/orbit/lint/UseOrbitLaunchOnSubscriptionRule.kt`:

```kotlin
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
```

- [ ] **Step 8: Create `OrbitRuleSetProvider`**

Create `orbit-lint/src/main/kotlin/org/orbitmvi/orbit/lint/OrbitRuleSetProvider.kt`:

```kotlin
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
```

- [ ] **Step 9: Register the RuleSetProvider**

Create `orbit-lint/src/main/resources/META-INF/services/io.gitlab.arturbosch.detekt.api.RuleSetProvider`:

```
org.orbitmvi.orbit.lint.OrbitRuleSetProvider
```

- [ ] **Step 10: Wire up the lint module in detekt config**

In `gradle/scripts/detekt.gradle.kts`, add the `orbit-lint` dependency at line 69 (inside the `dependencies` block):

```kotlin
dependencies {
    "detektPlugins"(libs.detektFormatting)
    "detektPlugins"(project(":orbit-lint"))
}
```

- [ ] **Step 11: Run lint tests**

Run: `./gradlew :orbit-lint:test`
Expected: All tests pass.

- [ ] **Step 12: Run detekt across the project to verify rules work**

Run: `./gradlew detekt`
Expected: Warnings reported for `repeatOnSubscription` usages in test files and for `coroutineScope { launch { } }` patterns in `SubIntentTest.kt`. The deprecated sample code was already migrated in Task 5.

- [ ] **Step 13: Commit**

```bash
git add orbit-lint/ settings.gradle.kts gradle/scripts/detekt.gradle.kts
git commit -m "feat: add detekt lint rules for orbit launch patterns (#281)"
```

---

### Task 7: Run full test suite and verify

**Files:** None (verification only)

- [ ] **Step 1: Run full test suite**

Run: `./gradlew allTests`
Expected: All tests pass across all modules.

- [ ] **Step 2: Run detekt**

Run: `./gradlew detekt`
Expected: No errors. Warnings for any remaining `repeatOnSubscription` usages in tests are expected — these tests validate the deprecated API still works.
