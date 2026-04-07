# Flow Collection API for `onCreate` (Issue #281)

## Problem

Collecting multiple flows in `onCreate` requires verbose boilerplate:

```kotlin
override val container = scope.orbitContainer<State, SideEffect>(initialState) {
    coroutineScope {
        launch {
            subIntent {
                flow1.collect { reduce { state.copy(field = it) } }
            }
        }
        launch {
            subIntent {
                flow2.collect { postSideEffect(SideEffect.Updated(it)) }
            }
        }
    }
}
```

There is no first-class API for launching non-blocking flow collections within the orbit DSL.

## Solution

Add two new methods to `Syntax`:

```kotlin
@OrbitDsl
suspend fun launch(block: suspend Syntax<S, SE>.() -> Unit)

@OrbitDsl
suspend fun launchOnSubscription(block: suspend Syntax<S, SE>.() -> Unit)
```

### `launch`

Launches a coroutine as a child of the current coroutine scope (structured concurrency). The parent intent stays alive while children run. Children are cancelled when the container is cancelled.

### `launchOnSubscription`

Same as `launch`, but subscriber-aware — the block runs when `refCountStateFlow`/`refCountSideEffectFlow` have active subscribers and is cancelled when subscribers reach zero (with debounce via `repeatOnSubscribedStopTimeout`). Uses the same `mapLatest` + `SubscribedCounter` mechanism as the existing `repeatOnSubscription`.

Replaces `repeatOnSubscription`, which will be deprecated.

## Usage

```kotlin
override val container = scope.orbitContainer<State, SideEffect>(initialState) {
    launch {
        someFlow.collect { value ->
            reduce { state.copy(field = value) }
        }
    }
    launch {
        anotherFlow.collect { value ->
            postSideEffect(SideEffect.Updated(value))
        }
    }
    launchOnSubscription {
        hotFlow.collect { value ->
            reduce { state.copy(data = value) }
        }
    }
}
```

## Design Details

### Structured Concurrency (Option B)

Both methods create child coroutines of the current coroutine scope. This means:

- The parent intent does not complete until all launched children complete
- Children are cancelled when the parent (intent job / container) is cancelled
- This matches the existing behavior of `coroutineScope { launch { } }` — the new API is syntactic sugar that eliminates boilerplate

### Syntax Receiver

Both methods provide a `Syntax<S, SE>` receiver inside the block, giving access to `reduce`, `postSideEffect`, `state`, and other DSL functions without requiring `subIntent` wrappers.

### Implementation Approach

Thread `CoroutineScope` through `ContainerContext`:

1. Add a `CoroutineScope` field to `ContainerContext`
2. In `RealContainer.initialiseIfNeeded()`, pass the intent's coroutine scope when executing intents:
   ```kotlin
   launch(exceptionHandlerContext) {
       val scopedContext = pluginContext.copy(scope = this)
       runCatching { scopedContext.intent() }.onFailure { ... }
   }
   ```
3. In `RealContainer.inlineOrbit()`, wrap in `coroutineScope` to provide the scope:
   ```kotlin
   override suspend fun inlineOrbit(...) {
       initialiseIfNeeded()
       coroutineScope {
           pluginContext.copy(scope = this).orbitIntent()
       }
   }
   ```
4. `Syntax.launch` delegates to the scope:
   ```kotlin
   suspend fun launch(block: suspend Syntax<S, SE>.() -> Unit) {
       containerContext.scope.launch {
           Syntax(containerContext).block()
       }
   }
   ```
5. `Syntax.launchOnSubscription` combines the scope launch with subscriber tracking:
   ```kotlin
   suspend fun launchOnSubscription(block: suspend Syntax<S, SE>.() -> Unit) {
       containerContext.scope.launch {
           containerContext.subscribedCounter.subscribed.mapLatest {
               if (it.isSubscribed) Syntax(containerContext).block() else null
           }.collect()
       }
   }
   ```

### Deprecation

`repeatOnSubscription` is deprecated:

```kotlin
@Deprecated(
    message = "Use launchOnSubscription instead",
    replaceWith = ReplaceWith("launchOnSubscription(block)")
)
suspend fun repeatOnSubscription(block: suspend CoroutineScope.() -> Unit)
```

Note: the receiver type changes from `CoroutineScope` to `Syntax<S, SE>`, so migration is not purely mechanical if users relied on the `CoroutineScope` receiver.

### Test Behavior

No test framework changes required:

- **Structured concurrency** means `runOnCreate()` returns a Job that covers all launched children
- **`AlwaysSubscribedCounter`** in tests makes `launchOnSubscription` blocks run immediately (same as today's `repeatOnSubscription`)
- Virtual time via `StandardTestDispatcher` works as before

### Lint Rules

Two custom lint rules:

1. **Flag `coroutineScope { launch { } }` inside orbit DSL blocks** — suggest replacing with `launch { }`
2. **Flag `repeatOnSubscription` usage** — suggest replacing with `launchOnSubscription`

Both rules should provide auto-fix suggestions where possible.

## Files to Modify

| File | Change |
|------|--------|
| `orbit-core/.../syntax/ContainerContext.kt` | Add `CoroutineScope` field |
| `orbit-core/.../syntax/Syntax.kt` | Add `launch` and `launchOnSubscription`, deprecate `repeatOnSubscription` |
| `orbit-core/.../internal/RealContainer.kt` | Pass coroutine scope when creating per-intent context |
| `orbit-core/.../ContainerHost.kt` | Update `subIntent` if needed for scope threading |
| `orbit-core/.../*Test.kt` | Add tests for new API |
| `orbit-lint/` (new or existing) | Lint rules for both patterns |
| Samples | Update to use new API |

## Not Included

- **`testTag` parameter** — the structured concurrency approach provides sufficient test lifecycle control. Tagging can be added later without breaking changes if needed.
