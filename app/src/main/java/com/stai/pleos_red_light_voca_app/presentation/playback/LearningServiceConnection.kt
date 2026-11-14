package com.stai.pleos_red_light_voca_app.presentation.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.stai.pleos_red_light_voca_app.domain.usecase.ObserveLearningStateUseCase
import com.stai.pleos_red_light_voca_app.presentation.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 학습 ON 동안 재생 서비스에 연결(바인딩)해 둔다. 정차 자동 재생은 서비스의 플레이어가 붙어 있어야 하는데,
 * 화면에서 학습을 시작하면 미디어 센터가 아직 바인딩하지 않아 서비스가 없을 수 있기 때문이다.
 * 명령은 보내지 않는다. 학습 OFF가 되면 연결을 끊는다. 메인 스레드에서만 다룬다.
 */
@Singleton
class LearningServiceConnection @Inject constructor(
    @ApplicationContext private val context: Context,
    private val observeLearningState: ObserveLearningStateUseCase,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private var job: Job? = null
    private var controller: ListenableFuture<MediaController>? = null

    /** 여러 번 불러도 한 번만 시작한다. */
    fun start() {
        if (job != null) return
        job = scope.launch {
            observeLearningState().map { it.learningOn }.distinctUntilChanged().collect { on ->
                if (on) connect() else release()
            }
        }
    }

    private fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, VocaPlaybackService::class.java))
        controller = MediaController.Builder(context, token).buildAsync()
        Timber.i("learning ON: keep playback service connected for auto-play")
    }

    private fun release() {
        val c = controller ?: return
        controller = null
        MediaController.releaseFuture(c)
        Timber.i("learning OFF: playback service connection released")
    }
}
