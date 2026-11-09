package com.stai.pleos_red_light_voca_app.presentation.playback

import com.stai.pleos_red_light_voca_app.domain.usecase.GetLearningStateUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.HandlePlayCommandUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.ObserveSessionStateUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.PrepareChapterAudioUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.ReportPauseCommandUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.ReportPlaybackUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.ReportStopCommandUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.ValidatePlayTicketUseCase
import javax.inject.Inject

/** [GatedPlayer]가 학습 세션을 다룰 때 쓰는 UseCase 묶음(서비스가 주입받아 플레이어를 만들 때 넘긴다). */
class GatedPlayerUseCases @Inject constructor(
    // 표시(현재 챕터)
    val observeSessionState: ObserveSessionStateUseCase,
    val getLearningState: GetLearningStateUseCase,
    // 컨트롤러 명령
    val handlePlayCommand: HandlePlayCommandUseCase,
    val reportPauseCommand: ReportPauseCommandUseCase,
    val reportStopCommand: ReportStopCommandUseCase,
    // 재생 경과·음성
    val reportPlayback: ReportPlaybackUseCase,
    val validatePlayTicket: ValidatePlayTicketUseCase,
    val prepareChapterAudio: PrepareChapterAudioUseCase,
)
