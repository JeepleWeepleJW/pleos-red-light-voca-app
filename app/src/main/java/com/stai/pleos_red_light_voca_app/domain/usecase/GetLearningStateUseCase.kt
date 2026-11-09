package com.stai.pleos_red_light_voca_app.domain.usecase

import com.stai.pleos_red_light_voca_app.domain.learning.LearningState
import com.stai.pleos_red_light_voca_app.domain.session.AppSession

/** 지금 학습 상태. 앱 준비(Ready) 전이면 null. */
class GetLearningStateUseCase(private val session: AppSession) {
    operator fun invoke(): LearningState? = session.controllerOrNull?.state?.value
}
