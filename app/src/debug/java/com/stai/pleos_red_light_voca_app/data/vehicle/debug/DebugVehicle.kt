package com.stai.pleos_red_light_voca_app.data.vehicle.debug

import com.stai.pleos_red_light_voca_app.domain.source.SourceMode
import timber.log.Timber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 디버그 빌드의 차량 입력 시뮬레이션 상태. 에뮬레이터에서 속도 주입이 확인되지 않아 기본은 FAKE.
 * 프로세스당 하나(di VariantModule의 @Singleton)를 시뮬레이터와 차량 소스 팩토리가 함께 쓴다.
 */
class DebugVehicle {
    val fake = FakeVehicleStateSource()

    private val _mode = MutableStateFlow(SourceMode.FAKE)
    val mode: StateFlow<SourceMode> = _mode.asStateFlow()

    fun setMode(mode: SourceMode) {
        _mode.value = mode
        Timber.i("debug source mode=$mode")
    }
}
