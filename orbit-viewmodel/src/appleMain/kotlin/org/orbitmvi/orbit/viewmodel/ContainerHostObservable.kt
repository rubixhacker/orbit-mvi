package org.orbitmvi.orbit.viewmodel

import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import com.rickclephas.kmp.nativecoroutines.NativeCoroutinesState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.orbitmvi.orbit.ContainerHost

public class ContainerHostObservable<STATE : Any, SIDE_EFFECT : Any>(
    private val host: ContainerHost<STATE, SIDE_EFFECT>
) {
    @NativeCoroutinesState
    public val stateFlow: StateFlow<STATE> = host.container.stateFlow

    @NativeCoroutines
    public val sideEffectFlow: Flow<SIDE_EFFECT> = host.container.sideEffectFlow
}

public fun <STATE : Any, SIDE_EFFECT : Any> ContainerHost<STATE, SIDE_EFFECT>.observe(): ContainerHostObservable<STATE, SIDE_EFFECT> =
    ContainerHostObservable(this)
