package com.stai.pleos_red_light_voca_app.domain.usecase

import com.stai.pleos_red_light_voca_app.domain.learning.LearningState
import com.stai.pleos_red_light_voca_app.domain.session.AppSession
import kotlinx.coroutines.flow.Flow

/** 학습 상태(학습 ON/OFF·현재 챕터·정차 판정·재생 단계 등)를 본다. 앱 준비(Ready) 뒤에만 내보낸다. */
class ObserveLearningStateUseCase(private val session: AppSession) {
    operator fun invoke(): Flow<LearningState> = session.learningState
}
