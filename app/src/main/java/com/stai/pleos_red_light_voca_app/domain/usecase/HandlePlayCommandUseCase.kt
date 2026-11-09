package com.stai.pleos_red_light_voca_app.domain.usecase

import com.stai.pleos_red_light_voca_app.core.common.Logger
import com.stai.pleos_red_light_voca_app.domain.learning.PlayDecision
import com.stai.pleos_red_light_voca_app.domain.playback.PlayCommandResult
import com.stai.pleos_red_light_voca_app.domain.playback.PlayGate
import com.stai.pleos_red_light_voca_app.domain.playback.PlaySource
import com.stai.pleos_red_light_voca_app.domain.playback.PlaySourcePolicy
import com.stai.pleos_red_light_voca_app.domain.session.AppSession
import com.stai.pleos_red_light_voca_app.domain.session.AudioStatus

/**
 * 컨트롤러 Play 명령(미디어 키·transport play)을 처리한다. 학습 세션의 Play 판정(`onPlayCommand`)에 닿는 유일한 길이다.
 *
 * 1. 앱(카탈로그)·음원 확인이 끝나지 않았으면(콜드 스타트): 미디어 키·디버그 컨트롤러는 끝날 때까지 기다렸다가
 *    같은 출처로 다시 처리하고, 그 밖의 출처는 무시한다.
 * 2. 출처 게이트([PlaySourcePolicy.gate]): 시스템 자동 재생은 무시, 학습 OFF면 사용자 키만, 선택 콘텐츠의 음원이 없으면 학습을 켜지 않는다.
 * 3. 학습 세션의 Play 판정. 학습만 켜졌으면(재생 없음) 현재 콘텐츠를 미리 병합한다.
 *
 * 앱과 음원 확인이 끝난 상태에서는 중단 없이 끝난다(호출한 코루틴이 UNDISPATCHED로 시작하면 같은 호출 안에서 결과가 나온다).
 * 기다리는 경우에만 중단한다. 메인 스레드에서 호출한다. [from]은 명령을 보낸 쪽(로그용).
 */
class HandlePlayCommandUseCase(
    private val session: AppSession,
    private val logger: Logger,
) {
    suspend operator fun invoke(source: PlaySource, from: String?): PlayCommandResult {
        val controller = session.controllerOrNull
        val learning = controller?.state?.value
        if (controller == null || learning == null || session.audio.value is AudioStatus.Checking) {
            if (source == PlaySource.MEDIA_KEY || source == PlaySource.SELF) {
                // 콜드 스타트 직후 누른 키를 버리지 않는다: 카탈로그·음원 확인이 끝나면 같은 출처로 다시 처리
                logger.i("media PLAY from=$from source=$source deferred until app/audio ready")
                if (session.awaitReadyForCommand()) return invoke(source, from)
                logger.i("media PLAY from=$from ignored: app failed to load")
            } else {
                logger.i("media PLAY from=$from source=$source ignored: app not ready")
            }
            return PlayCommandResult.Ignored
        }
        val audioReady = session.audio.value.isReadyFor(learning.contentId)
        when (PlaySourcePolicy.gate(source, learning.learningOn, audioReady)) {
            PlayGate.FORWARD -> Unit
            PlayGate.IGNORE_NOT_USER_KEY -> {
                logger.i("media PLAY from=$from source=$source ignored: learning OFF, only a media key starts learning")
                return PlayCommandResult.Ignored
            }
            PlayGate.IGNORE_SYSTEM_AUTOPLAY -> {
                logger.i("media PLAY from=$from source=$source ignored: system auto-play (no learning ON, no playback)")
                return PlayCommandResult.Ignored
            }
            PlayGate.IGNORE_AUDIO_NOT_READY -> {
                // 학습 OFF에서 Play는 학습 ON 전환인데, 선택 콘텐츠의 음원이 없으면 학습을 시작하지 않는다
                logger.i("media PLAY from=$from ignored: learning OFF and audio not ready (${session.audio.value.refusalFor(learning.contentId)})")
                return PlayCommandResult.Ignored
            }
        }
        logger.i("media PLAY from=$from source=$source -> LearningController")
        return when (val decision = controller.onPlayCommand(from)) {
            is PlayDecision.Play -> PlayCommandResult.Play(decision.ticket)
            PlayDecision.LearningEnabledOnly -> {
                session.prefetchActiveContent()
                PlayCommandResult.Forwarded
            }
            is PlayDecision.Ignored -> PlayCommandResult.Forwarded
        }
    }
}
