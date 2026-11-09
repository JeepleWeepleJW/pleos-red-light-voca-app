package com.stai.pleos_red_light_voca_app.core.entity

import com.stai.pleos_red_light_voca_app.core.common.metersPerSecondToKmh

/** 기어. Pleos `VehicleGear.DRIVE`만 [DRIVE]. GEAR_1~9·P·R·N 등은 [OTHER], 판별 불가는 [UNKNOWN]. */
enum class Gear { DRIVE, OTHER, UNKNOWN }

enum class VehicleSignal { SPEED, GEAR }

/** 일시적 실패. 감지를 초기화하고 복구 후 충분한 주행을 다시 확인한다. */
enum class SignalFailure {
    /** 속성 값 상태가 unavailable */
    UNAVAILABLE,
    /** 속성 값 상태가 error 또는 오류 콜백 */
    ERROR,
    /** 현재값 조회 실패(예외, null) */
    READ_FAILED,
    /** 차량 서비스(Car / Pleos Vehicle Service) 연결 끊김 */
    DISCONNECTED,
}

/** 지속적 차단. 학습을 비활성화한다. 해당 신호의 유효한 값을 다시 받으면 풀린다. */
enum class SignalBlock { UNSUPPORTED, PERMISSION_DENIED }

/** 차량 입력원(domain VehicleStateSource)이 내보내는 입력 */
sealed interface VehicleEvent {
    /** 속도. km/h, 0 이상(abs 적용 후). m/s 원값은 [metersPerSecondToKmh]로 변환한다. */
    data class SpeedChanged(val speedKmh: Float) : VehicleEvent

    data class GearChanged(val gear: Gear) : VehicleEvent

    data class SignalFailed(val signal: VehicleSignal, val failure: SignalFailure) : VehicleEvent

    data class SignalBlocked(val signal: VehicleSignal, val block: SignalBlock) : VehicleEvent
}
