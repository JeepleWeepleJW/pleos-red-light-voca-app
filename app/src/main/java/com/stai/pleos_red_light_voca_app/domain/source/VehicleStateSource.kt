package com.stai.pleos_red_light_voca_app.domain.source

import com.stai.pleos_red_light_voca_app.core.entity.Gear
import com.stai.pleos_red_light_voca_app.core.entity.VehicleEvent

import kotlinx.coroutines.flow.Flow

/**
 * 차량 상태 입력 경계(실제 입력: Pleos Gear + CarPropertyManager 속도).
 * 실제 구현은 data `VehicleStateSourceImpl`, 디버그 시뮬레이션은 src/debug data의 `FakeVehicleStateSource`·`SwitchingVehicleStateSource`다.
 *
 * 계약:
 * - cold Flow. 수집을 시작하면 연결·구독하고, 먼저 현재값(또는 실패/차단)을 내보낸다. 수집을 취소하면 구독을 해제한다.
 * - 값이 바뀌지 않으면 이벤트를 반복해 보낼 필요가 없다(같은 값 미반복은 오류가 아니다).
 * - 연결이 끊기면 끊긴 신호에 [VehicleEvent.SignalFailed] (DISCONNECTED)를 보낸다. 한 입력원이 두 신호를 함께 주면 두 신호 모두,
 *   신호마다 입력원이 다르면(실제 구현: 속도는 Car 서비스, 기어는 Pleos Vehicle Service) 끊긴 쪽만 보낸다.
 */
interface VehicleStateSource {
    fun events(): Flow<VehicleEvent>
}
