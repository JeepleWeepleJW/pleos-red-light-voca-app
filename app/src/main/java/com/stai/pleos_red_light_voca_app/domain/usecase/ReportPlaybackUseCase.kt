package com.stai.pleos_red_light_voca_app.domain.usecase

import com.stai.pleos_red_light_voca_app.domain.learning.PlaybackReport
import com.stai.pleos_red_light_voca_app.domain.session.AppSession

/** 재생 시작·완료·중단을 학습 세션에 알린다. 앱 준비 전이면 무시된다. */
class ReportPlaybackUseCase(private val session: AppSession) {
    operator fun invoke(report: PlaybackReport) {
        val controller = session.controllerOrNull ?: return
        when (report) {
            is PlaybackReport.Started -> controller.onPlaybackStarted(report.ticket)
            is PlaybackReport.Ended -> controller.onPlaybackEnded(report.ticket)
            is PlaybackReport.Interrupted -> controller.onPlaybackInterrupted(report.ticket, report.reason)
        }
    }
}
