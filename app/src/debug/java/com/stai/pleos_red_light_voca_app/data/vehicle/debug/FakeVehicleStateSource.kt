package com.stai.pleos_red_light_voca_app.data.vehicle.debug

import com.stai.pleos_red_light_voca_app.core.entity.Gear
import com.stai.pleos_red_light_voca_app.core.entity.SignalBlock
import com.stai.pleos_red_light_voca_app.core.entity.SignalFailure
import com.stai.pleos_red_light_voca_app.core.entity.VehicleEvent
import com.stai.pleos_red_light_voca_app.core.entity.VehicleSignal
import com.stai.pleos_red_light_voca_app.domain.source.VehicleStateSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onSubscription

/** 디버그 시뮬레이션 입력. 순수 Kotlin. 구독 직후 현재 속도·기어를 먼저 내보내고 이후 변경을 내보낸다. */
class FakeVehicleStateSource : VehicleStateSource {
    private val _speedKmh = MutableStateFlow(0f)
    private val _gear = MutableStateFlow(Gear.OTHER)
    private val _gearLabel = MutableStateFlow("GEAR_PARK")
    private val changes = MutableSharedFlow<VehicleEvent>(extraBufferCapacity = 64)

    val speedKmh: StateFlow<Float> = _speedKmh.asStateFlow()
    val gear: StateFlow<Gear> = _gear.asStateFlow()
    val gearLabel: StateFlow<String> = _gearLabel.asStateFlow()

    private val lock = Any()

    fun setSpeed(kmh: Float) {
        synchronized(lock) {
            _speedKmh.value = kmh
            changes.tryEmit(VehicleEvent.SpeedChanged(kmh))
        }
    }

    /** 라벨을 해석해 기어를 바꾼다. 모르는 문자열이면 false(변경 없음). */
    fun setGear(label: String): Boolean {
        val g = parseGear(label) ?: return false
        synchronized(lock) {
            _gear.value = g
            _gearLabel.value = label.trim().uppercase()
            changes.tryEmit(VehicleEvent.GearChanged(g))
        }
        return true
    }

    fun fail(signal: VehicleSignal, failure: SignalFailure) {
        changes.tryEmit(VehicleEvent.SignalFailed(signal, failure))
    }

    fun block(signal: VehicleSignal, block: SignalBlock) {
        changes.tryEmit(VehicleEvent.SignalBlocked(signal, block))
    }

    override fun events(): Flow<VehicleEvent> = changes.onSubscription {
        // 구독 등록 후 스냅샷을 내보내므로 구독과 스냅샷 사이 변경을 놓치지 않는다.
        emit(VehicleEvent.SpeedChanged(_speedKmh.value))
        emit(VehicleEvent.GearChanged(_gear.value))
    }

    companion object {
        fun parseGear(label: String): Gear? = when (label.trim().uppercase()) {
            "DRIVE", "D", "GEAR_DRIVE" -> Gear.DRIVE
            "UNKNOWN", "GEAR_UNKNOWN" -> Gear.UNKNOWN
            "PARK", "P", "GEAR_PARK", "REVERSE", "R", "GEAR_REVERSE",
            "NEUTRAL", "N", "GEAR_NEUTRAL",
            "GEAR_1", "GEAR_2", "GEAR_3", "GEAR_4", "GEAR_5",
            "GEAR_6", "GEAR_7", "GEAR_8", "GEAR_9",
            -> Gear.OTHER
            else -> null
        }
    }
}
