package com.stai.pleos_red_light_voca_app.data.vehicle.debug

import timber.log.Timber
import com.stai.pleos_red_light_voca_app.core.entity.SignalFailure
import com.stai.pleos_red_light_voca_app.core.entity.VehicleEvent
import com.stai.pleos_red_light_voca_app.core.entity.VehicleSignal
import com.stai.pleos_red_light_voca_app.domain.source.SourceMode
import com.stai.pleos_red_light_voca_app.domain.source.VehicleStateSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart

/** 모드에 따라 실제/가짜 입력을 바꿔 끼운다. 전환하면 이전 소스 수집은 취소되고 새 소스의 현재값부터 다시 시작한다. */
class SwitchingVehicleStateSource(
    private val real: VehicleStateSource,
    private val fake: VehicleStateSource,
    private val mode: StateFlow<SourceMode>,
    private val log: (String) -> Unit = { Timber.i(it) },
) : VehicleStateSource {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun events(): Flow<VehicleEvent> = flow {
        var first = true
        emitAll(
            mode.flatMapLatest { m ->
                val src = if (m == SourceMode.FAKE) fake else real
                val switched = !first
                first = false
                flow {
                    // 입력원 교체 = 단절로 보고 이전 판정 이력을 초기화시킨다(첫 모드 제외).
                    if (switched) {
                        emit(VehicleEvent.SignalFailed(VehicleSignal.SPEED, SignalFailure.DISCONNECTED))
                        emit(VehicleEvent.SignalFailed(VehicleSignal.GEAR, SignalFailure.DISCONNECTED))
                    }
                    emitAll(src.events().onStart { log("debug vehicle source switched to $m") })
                }
            },
        )
    }
}
