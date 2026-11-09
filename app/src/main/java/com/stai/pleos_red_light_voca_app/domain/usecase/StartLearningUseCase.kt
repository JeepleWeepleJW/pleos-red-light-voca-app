package com.stai.pleos_red_light_voca_app.domain.usecase

import com.stai.pleos_red_light_voca_app.domain.session.AppSession
import com.stai.pleos_red_light_voca_app.domain.session.StartOutcome

/** 화면의 "학습 시작". 그 콘텐츠의 음원이 모두 있지 않으면 시작하지 않는다. 재생은 시작하지 않는다. */
class StartLearningUseCase(private val session: AppSession) {
    suspend operator fun invoke(contentId: String): StartOutcome = session.startLearning(contentId)
}
