# Integrating Orbit with The Composable Architecture (TCA)

This guide describes how to integrate Orbit MVI (Kotlin Multiplatform) with The Composable Architecture (Swift) on iOS. This hybrid approach allows you to share business logic and state management via Orbit (Common code) while leveraging TCA for the UI layer and side effects handling on iOS.

## Prerequisites

### 1. Export Orbit Framework
Ensure your shared module exports the `orbit-viewmodel` library so `ContainerHost` and related classes are available to Swift.

In your shared module's `build.gradle.kts`:

```kotlin
kotlin {
    // ...
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    cocoapods { // Or framework block
        // ...
        framework {
            export(project(":orbit-viewmodel"))
        }
    }
}
```

### 2. Concurrency Bridge (Recommended)
While you can bridge Kotlin Flows to Swift manually, we highly recommend using [KMP-NativeCoroutines](https://github.com/rickclephas/KMP-NativeCoroutines) to automatically generate `AsyncSequence` wrappers for your `StateFlow` and `SharedFlow`.

If you are not using KMP-NativeCoroutines, you will need to implement a small wrapper to consume Kotlin Flows as Swift AsyncSequences.

## The Pattern

The core idea is to treat the Orbit `ContainerHost` as a **Dependency** within the TCA system.

1.  **State**: The TCA `State` should mirror or wrap the Orbit `State`.
2.  **Action**: TCA `Actions` map to Orbit **Intents** (functions) or handle updates from Orbit (State changes, Side effects).
3.  **Reducer**: The `Reducer` manages the connection. On `onAppear` (or initialization), it starts a long-running `Effect` that observes Orbit's `stateFlow` and `sideEffectFlow`.

## Implementation Example

Assumptions:
*   You have a shared generic `CalculatorViewModel` implementing `ContainerHost<CalculatorState, CalculatorSideEffect>`.
*   You are using `KMP-NativeCoroutines` (generating `asyncSequence(for:)`).

### 1. Define the Dependency

First, expose your Orbit ViewModel to TCA. Using the Dependencies library (included in TCA):

```swift
import ComposableArchitecture
import Shared // Your shared KMP framework

struct OrbitClient {
    var viewModel: CalculatorViewModel
}

extension OrbitClient: DependencyKey {
    static var liveValue: OrbitClient = {
        // Initialize your shared ViewModel
        // You might need to pass dependencies here or use a DI container from the Kotlin side
        return OrbitClient(viewModel: CalculatorViewModel())
    }()
}

extension DependencyValues {
    var calculatorOrbit: OrbitClient {
        get { self[OrbitClient.self] }
        set { self[OrbitClient.self] = newValue }
    }
}
```

### 2. The TCA Feature

```swift
import ComposableArchitecture
import Shared
import KMPNativeCoroutinesAsync // If using KMP-NativeCoroutines

@Reducer
struct CalculatorFeature {

    // TCA State mirrors the Orbit State
    @ObservableState
    struct State: Equatable {
        var count: Int = 0
        // Add other properties from CalculatorState
    }

    enum Action {
        // User Actions
        case incrementTapped
        case decrementTapped

        // Orbit Updates
        case orbitStateUpdated(CalculatorState)
        case orbitSideEffect(CalculatorSideEffect)

        // Lifecycle
        case task
    }

    @Dependency(\.calculatorOrbit) var orbitClient

    var body: some Reducer<State, Action> {
        Reduce { state, action in
            switch action {

            // --- User Interactions ---
            case .incrementTapped:
                // Dispatch intent to Orbit
                orbitClient.viewModel.increment()
                return .none

            case .decrementTapped:
                orbitClient.viewModel.decrement()
                return .none

            // --- Orbit Integration ---
            case .task:
                return .merge(
                    // Observe State
                    .run { send in
                        // Using KMP-NativeCoroutines to get AsyncSequence
                        let stateSequence = asyncSequence(for: orbitClient.viewModel.container.stateFlow)
                        for try await orbitState in stateSequence {
                            await send(.orbitStateUpdated(orbitState))
                        }
                    },

                    // Observe Side Effects
                    .run { send in
                        let sideEffectSequence = asyncSequence(for: orbitClient.viewModel.container.sideEffectFlow)
                        for try await sideEffect in sideEffectSequence {
                            await send(.orbitSideEffect(sideEffect))
                        }
                    }
                )

            case let .orbitStateUpdated(orbitState):
                // Sync TCA state with Orbit state
                state.count = Int(orbitState.count)
                return .none

            case let .orbitSideEffect(effect):
                // Handle one-off events (Toast, Navigation, etc.)
                switch effect {
                case let toast as CalculatorSideEffect.Toast:
                    print("Toast: \(toast.text)")
                default:
                    break
                }
                return .none
            }
        }
    }
}
```

### 3. The View

The view interacts only with the TCA Store, unaware of the underlying Kotlin implementation.

```swift
struct CalculatorView: View {
    let store: StoreOf<CalculatorFeature>

    var body: some View {
        VStack {
            Text("Count: \(store.count)")
            HStack {
                Button("-") { store.send(.decrementTapped) }
                Button("+") { store.send(.incrementTapped) }
            }
        }
        .task {
            // Start the connection when the view appears
            await store.send(.task).finish()
        }
    }
}
```

## Manual Flow Bridging (If not using KMP-NativeCoroutines)

If you are not using a library to bridge flows, you can create a simple generic wrapper.

```swift
import Shared

class FlowWrapper<T> {
    let flow: Kotlinx_coroutines_coreFlow

    init(_ flow: Kotlinx_coroutines_coreFlow) {
        self.flow = flow
    }

    func stream() -> AsyncThrowingStream<T, Error> {
        return AsyncThrowingStream { continuation in
            let collector = FlowCollector<T> { value in
                continuation.yield(value)
            }

            // Launch a collection job.
            // Note: This requires proper scope management in a real app.
            // A simpler way often involves passing a callback from Kotlin.

            // For pure Swift consumption without KMP-NativeCoroutines,
            // you might need to expose a `watch` function in Kotlin
            // that takes a callback, rather than using Flow directly.
        }
    }
}
```

**Note:** Bridging raw Kotlin Flows to Swift `AsyncSequence` correctly involves handling cancellation and threading. We strongly recommend **KMP-NativeCoroutines** or **SKIE** for this purpose.
