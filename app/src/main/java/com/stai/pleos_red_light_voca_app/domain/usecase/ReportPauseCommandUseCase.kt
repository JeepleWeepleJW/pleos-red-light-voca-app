package com.stai.pleos_red_light_voca_app.domain.usecase

import com.stai.pleos_red_light_voca_app.domain.session.AppSession

/** 컨트롤러의 Pause 명령을 학습 세션에 알린다. 앱 준비 전이면 무시된다. */
class ReportPauseCommandUseCase(private val session: AppSession) {
    operator fun invoke(from: String?) {
        session.controllerOrNull?.onPauseCommand(from)
    }
}
