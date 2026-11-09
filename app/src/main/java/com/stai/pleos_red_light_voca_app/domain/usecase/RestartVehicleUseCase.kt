package com.stai.pleos_red_light_voca_app.domain.usecase

import com.stai.pleos_red_light_voca_app.domain.session.AppSession

/** 차량 입력 수집을 다시 시작한다(권한을 새로 받았을 때). 앱 준비 전이면 무시된다. */
class RestartVehicleUseCase(private val session: AppSession) {
    operator fun invoke() = session.restartVehicle()
}
