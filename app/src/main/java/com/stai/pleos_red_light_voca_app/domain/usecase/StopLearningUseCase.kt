package com.stai.pleos_red_light_voca_app.domain.usecase

import com.stai.pleos_red_light_voca_app.domain.session.AppSession

/** 학습을 끈다. 앱 준비 전이면 무시된다. */
class StopLearningUseCase(private val session: AppSession) {
    operator fun invoke() = session.stopLearning()
}
