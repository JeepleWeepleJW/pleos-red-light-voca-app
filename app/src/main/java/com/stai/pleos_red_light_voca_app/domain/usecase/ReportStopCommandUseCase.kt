package com.stai.pleos_red_light_voca_app.domain.usecase

import com.stai.pleos_red_light_voca_app.domain.session.AppSession

/** 컨트롤러의 Stop 명령을 학습 세션에 알린다(재생만 멈추고 학습은 유지). 앱 준비 전이면 무시된다. */
class ReportStopCommandUseCase(private val session: AppSession) {
    operator fun invoke(from: String?) {
        session.controllerOrNull?.onStopCommand(from)
    }
}
