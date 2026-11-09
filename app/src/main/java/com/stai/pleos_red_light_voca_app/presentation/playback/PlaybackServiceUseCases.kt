package com.stai.pleos_red_light_voca_app.presentation.playback

import com.stai.pleos_red_light_voca_app.domain.usecase.AttachPlayerUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.DetachPlayerUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.GetLearningStateUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.ObserveLearningStateUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.ObserveSessionStateUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.StopLearningUseCase
import javax.inject.Inject

/** [VocaPlaybackService]가 서비스 수명·라이브러리 응답에 쓰는 UseCase 묶음. */
class PlaybackServiceUseCases @Inject constructor(
    // 상태(세션 갱신·라이브러리 응답)
    val observeSessionState: ObserveSessionStateUseCase,
    val observeLearningState: ObserveLearningStateUseCase,
    val getLearningState: GetLearningStateUseCase,
    // 서비스 수명
    val attachPlayer: AttachPlayerUseCase,
    val detachPlayer: DetachPlayerUseCase,
    val stopLearning: StopLearningUseCase,
)
