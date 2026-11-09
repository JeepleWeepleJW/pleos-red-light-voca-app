package com.stai.pleos_red_light_voca_app.data.vehicle

import com.stai.pleos_red_light_voca_app.core.entity.Gear
import com.stai.pleos_red_light_voca_app.core.entity.SignalBlock
import com.stai.pleos_red_light_voca_app.core.entity.SignalFailure
import com.stai.pleos_red_light_voca_app.core.entity.VehicleEvent
import com.stai.pleos_red_light_voca_app.core.entity.VehicleSignal
import com.stai.pleos_red_light_voca_app.core.common.metersPerSecondToKmh

/**
 * android·Pleos 타입에 의존하지 않는 순수 매핑. 문자열/정수로 받아 JVM 단위 테스트가 가능하다.
 */

/** Pleos `VehicleGear.name` → [Gear]. GEAR_DRIVE만 DRIVE, GEAR_UNKNOWN·null은 UNKNOWN, 나머지는 OTHER. */
fun mapGearName(name: String?): Gear = when (name) {
    "GEAR_DRIVE" -> Gear.DRIVE
    null, "GEAR_UNKNOWN" -> Gear.UNKNOWN
    else -> Gear.OTHER
}

/**
 * Pleos onFailed 예외(난독화되어 타입 구분 불가) → 이벤트. 메시지의 "ERROR_XXX" 이름으로 분류한다.
 * @param isSecurityException 예외가 SecurityException이면 true
 */
fun classifyPleosFailure(
    signal: VehicleSignal,
    message: String?,
    isSecurityException: Boolean = false,
): VehicleEvent {
    val m = message.orEmpty()
    return when {
        isSecurityException || m.contains("ERROR_SECURITY_PERMISSION") ->
            VehicleEvent.SignalBlocked(signal, SignalBlock.PERMISSION_DENIED)
        m.contains("ERROR_NOT_SUPPORTED") ->
            VehicleEvent.SignalBlocked(signal, SignalBlock.UNSUPPORTED)
        m.contains("ERROR_SERVICE_CONNECTION_FAILURE") ->
            VehicleEvent.SignalFailed(signal, SignalFailure.DISCONNECTED)
        m.contains("ERROR_EMPTY_RESULT") ->
            VehicleEvent.SignalFailed(signal, SignalFailure.READ_FAILED)
        else -> VehicleEvent.SignalFailed(signal, SignalFailure.ERROR)
    }
}

/**
 * Pleos 오류 메시지가 재연결(release 후 initialize)로 회복을 기대하는 연결 계열인지.
 * Pleos 오류 문서가 ERROR_SERVICE_CONNECTION_FAILURE·ERROR_INTERNAL_SERVICE에 "initialize·재시도"를 권한다.
 */
fun isPleosConnectionError(message: String?): Boolean {
    val m = message.orEmpty()
    return m.contains("ERROR_SERVICE_CONNECTION_FAILURE") || m.contains("ERROR_INTERNAL_SERVICE")
}

// android.car.hardware.CarPropertyValue 상수와 같은 값: STATUS_AVAILABLE=0, STATUS_UNAVAILABLE=1, STATUS_ERROR=2.
// 구현 클래스(CarSpeedDataSource)는 실제 상수를 넘긴다.
const val CAR_STATUS_AVAILABLE = 0
const val CAR_STATUS_UNAVAILABLE = 1
const val CAR_STATUS_ERROR = 2

/** CarPropertyValue 상태 정수 + 값(m/s) → 속도 이벤트 */
fun mapSpeedStatus(
    status: Int,
    valueMetersPerSecond: Float?,
    availableStatus: Int = CAR_STATUS_AVAILABLE,
    unavailableStatus: Int = CAR_STATUS_UNAVAILABLE,
    errorStatus: Int = CAR_STATUS_ERROR,
): VehicleEvent = when (status) {
    availableStatus ->
        if (valueMetersPerSecond == null || !valueMetersPerSecond.isFinite()) {
            VehicleEvent.SignalFailed(VehicleSignal.SPEED, SignalFailure.READ_FAILED)
        } else {
            VehicleEvent.SpeedChanged(metersPerSecondToKmh(valueMetersPerSecond))
        }
    unavailableStatus -> VehicleEvent.SignalFailed(VehicleSignal.SPEED, SignalFailure.UNAVAILABLE)
    errorStatus -> VehicleEvent.SignalFailed(VehicleSignal.SPEED, SignalFailure.ERROR)
    else -> VehicleEvent.SignalFailed(VehicleSignal.SPEED, SignalFailure.ERROR)
}
