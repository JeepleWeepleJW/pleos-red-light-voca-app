package com.stai.pleos_red_light_voca_app.domain.source

/** 차량 입력 수집을 (다시) 시작할 때마다 새 [VehicleStateSource]를 만든다. */
fun interface VehicleStateSourceFactory {
    fun create(): VehicleStateSource
}
