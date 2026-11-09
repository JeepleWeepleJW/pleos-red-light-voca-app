package com.stai.pleos_red_light_voca_app.presentation.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.stai.pleos_red_light_voca_app.presentation.learning.ListenCommand
import timber.log.Timber

/**
 * 화면 "듣기" 버튼의 명령을 재생 서비스의 MediaSession에 보낸다(모든 빌드). 메인 스레드에서 호출한다.
 * 연결 힌트 [PlaySourceClassifier.APP_UI_CONTROLLER_HINT]로 자신을 화면 버튼(APP_UI)으로 알린다.
 * 세션은 이 명령을 다른 컨트롤러 명령과 같은 게이트로 처리하므로, 앱이 플레이어 play()를 직접 부르는 경로가 아니다.
 * 컨트롤러가 연결되면 서비스가 없을 때 서비스가 만들어진다(바인딩).
 */
object AppMediaController {
    /**
     * `sent` 로그는 명령을 컨트롤러 큐에 넣은 시점이다(세션이 처리하기 전). 실제 처리·거절은 세션 쪽 `media PLAY …`·`media PAUSE …` 로그로 본다.
     * 연결에 실패하면 `failed` 로그만 남고 화면에는 변화가 없다.
     */
    fun send(context: Context, command: ListenCommand) {
        val app = context.applicationContext
        val what = command.name.lowercase()
        val token = SessionToken(app, ComponentName(app, VocaPlaybackService::class.java))
        val hints = Bundle().apply { putBoolean(PlaySourceClassifier.APP_UI_CONTROLLER_HINT, true) }
        val future = MediaController.Builder(app, token).setConnectionHints(hints).buildAsync()
        future.addListener({
            try {
                val c = future.get()
                dispatch(command, play = c::play, pause = c::pause)
                Timber.i("app ui listen cmd=$what sent")
                c.release()
            } catch (e: Exception) {
                Timber.w("app ui listen cmd=$what failed ${e.javaClass.simpleName} ${e.message}")
            }
        }, MoreExecutors.directExecutor())
    }

    /** 듣기 명령 → 컨트롤러 호출(순수, 단위 테스트용). */
    internal fun dispatch(command: ListenCommand, play: () -> Unit, pause: () -> Unit) = when (command) {
        ListenCommand.PLAY -> play()
        ListenCommand.PAUSE -> pause()
    }
}
