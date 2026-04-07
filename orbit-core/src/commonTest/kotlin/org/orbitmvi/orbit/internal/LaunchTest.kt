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
