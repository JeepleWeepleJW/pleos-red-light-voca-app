package com.stai.pleos_red_light_voca_app.presentation.debug

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import timber.log.Timber
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.stai.pleos_red_light_voca_app.presentation.playback.PlaySourceClassifier
import com.stai.pleos_red_light_voca_app.presentation.playback.VocaPlaybackService

/**
 * 재생 서비스에 MediaSession 명령을 보내는 디버그 도구. 메인 스레드에서 호출한다.
 * 연결 힌트로 자신을 debug 컨트롤러(SELF)로 알린다. 세션은 debuggable 빌드에서만 이 힌트를 인정한다.
 * 이 경로에는 시스템 미디어 키의 포그라운드 서비스 임시 허용이 붙지 않으므로 ADR-001 PoC-2 확인에는 쓰지 않는다.
 */
object DebugMedia {
    val COMMANDS = setOf("play", "pause", "stop", "playpause")

    fun send(context: Context, cmd: String, onDone: () -> Unit = {}) {
        val app = context.applicationContext
        val token = SessionToken(app, ComponentName(app, VocaPlaybackService::class.java))
        val hints = Bundle().apply { putBoolean(PlaySourceClassifier.DEBUG_CONTROLLER_HINT, true) }
        val future = MediaController.Builder(app, token).setConnectionHints(hints).buildAsync()
        future.addListener({
            try {
                val c = future.get()
                when (cmd) {
                    "play" -> c.play()
                    "pause" -> c.pause()
                    "stop" -> c.stop()
                    "playpause" ->
                        if (c.playWhenReady && c.playbackState != Player.STATE_ENDED) c.pause() else c.play()
                }
                Timber.i("debug media cmd=$cmd sent")
                c.release()
            } catch (e: Exception) {
                Timber.w("debug media cmd=$cmd failed ${e.javaClass.simpleName} ${e.message}")
            } finally {
                onDone()
            }
        }, MoreExecutors.directExecutor())
    }
}
