package com.stai.pleos_red_light_voca_app.domain.source

import com.stai.pleos_red_light_voca_app.core.entity.SignalBlock
import com.stai.pleos_red_light_voca_app.core.entity.SignalFailure
import com.stai.pleos_red_light_voca_app.core.entity.VehicleSignal
import kotlinx.coroutines.flow.StateFlow

/** 디버그 빌드의 차량 입력 소스: 실제 입력(REAL) 또는 시뮬레이션(FAKE) */
enum class SourceMode { REAL, FAKE }

/**
 * 디버그 빌드 전용 차량 입력 시뮬레이터. 디버그 패널과 adb 리시버가 이 인터페이스로만 조작한다.
 * 주입(속도·기어·실패·차단)은 [mode]가 FAKE일 때 학습 세션에 전달된다.
 */
interface VehicleSimulator {
    /** 지금 쓰는 차량 입력 소스 */
    val mode: StateFlow<SourceMode>

    /** 시뮬레이션 속도(km/h) */
    val speedKmh: StateFlow<Float>

    /** 시뮬레이션 기어 라벨(마지막으로 넣은 문자열, 대문자) */
    val gearLabel: StateFlow<String>

    /** 차량 입력 소스를 바꾼다. 바꾸면 학습 세션은 단절 뒤 새 소스의 현재값부터 받는다. */
    fun setMode(mode: SourceMode)

    fun setSpeed(kmh: Float)

    /** 라벨(DRIVE, D, GEAR_DRIVE, PARK 등)로 기어를 바꾼다. 모르는 라벨이면 false(변경 없음). */
    fun setGear(label: String): Boolean

    fun fail(signal: VehicleSignal, failure: SignalFailure)

    fun block(signal: VehicleSignal, block: SignalBlock)
}
