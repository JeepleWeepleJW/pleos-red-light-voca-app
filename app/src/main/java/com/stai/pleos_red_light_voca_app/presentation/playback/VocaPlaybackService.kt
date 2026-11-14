package com.stai.pleos_red_light_voca_app.presentation.playback

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.stai.pleos_red_light_voca_app.presentation.ApplicationScope
import com.stai.pleos_red_light_voca_app.presentation.MainActivity
import com.stai.pleos_red_light_voca_app.domain.session.SessionState
import timber.log.Timber
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Media3 MediaLibraryService. 매니페스트에서 `androidx.media3.session.MediaLibraryService`와
 * `android.media.browse.MediaBrowserService` intent-filter로 노출한다(Pleos Music/Audio 가이드).
 *
 * - 재생 시작은 [GatedPlayer]가 한다: 학습 ON 중 정차 확정 시 자동 재생, 또는 컨트롤러 Play 명령. 서비스 생성·연결로는 재생하지 않는다.
 * - 포그라운드 서비스(mediaPlayback)는 Media3가 재생 중에 올린다. 알림 갱신 오버라이드로 유지하지 않는다.
 * - onTaskRemoved: 재생 정지 → stopSelf() → super (Pleos 가이드 순서).
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class VocaPlaybackService : MediaLibraryService() {

    @Inject lateinit var useCases: PlaybackServiceUseCases

    /** 플레이어를 만들 때 넘긴다. 서비스는 쓰지 않는다. */
    @Inject lateinit var playerUseCases: GatedPlayerUseCases

    /** 앱(프로세스) 단위 메인 스레드 스코프 */
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope
    private lateinit var exo: ExoPlayer
    private lateinit var player: GatedPlayer
    private var session: MediaLibrarySession? = null
    private var stateJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        Timber.i("service onCreate")
        exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    // MUSIC: 내비 안내(MAY_DUCK) 때 pause 대신 duck(Pleos 포커스 가이드: duck 중 pause는 C를 깬다)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .build()
        player = GatedPlayer(
            Looper.getMainLooper(),
            exo,
            playerUseCases,
            appScope,
            debuggableBuild = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            ownPackage = packageName,
            onPlaybackStartedHook = ::logForegroundState)
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val s = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(sessionActivity)
            .build()
        session = s
        player.session = s
        useCases.attachPlayer(player)
        setListener(object : MediaSessionService.Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                Timber.w("FGS start NOT allowed (ForegroundServiceStartNotAllowedException caught by Media3)")
            }
        })
        stateJob = appScope.launch {
            useCases.observeSessionState().collect { st ->
                if (st is SessionState.Ready) {
                    player.refresh()
                    useCases.observeLearningState().collect { player.refresh() }
                }
            }
        }
        // Pleos 가이드: bind/unbind만으로는 onTaskRemoved를 못 받을 수 있으므로 Started 상태로 만든다.
        try {
            startService(Intent(this, VocaPlaybackService::class.java))
            Timber.i("service startService(self) ok")
        } catch (e: IllegalStateException) {
            Timber.w(e, "service startService(self) not allowed in background")
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        Timber.i("session connect from=${controllerInfo.packageName}")
        return session
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Timber.i("service onTaskRemoved: stop playback -> stopSelf -> super")
        useCases.stopLearning()
        player.onServiceEnding()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Timber.i("service onDestroy")
        stateJob?.cancel()
        handler.removeCallbacksAndMessages(null)
        useCases.detachPlayer(player)
        player.onServiceEnding()
        // 요구 1장: 서비스가 재시작되면 학습은 OFF(진도만 유지). 프로세스가 살아 서비스만 다시 만들어져도 같게 한다.
        useCases.stopLearning()
        session?.release()
        session = null
        player.releaseInternal()
        player.release()
        exo.release()
        super.onDestroy()
    }

    /** 재생 시작 1초 뒤 실제 포그라운드 서비스 여부를 로그로 남긴다(ADR-001 PoC-2 확인용). */
    private fun logForegroundState() {
        handler.postDelayed({
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val type = foregroundServiceType
                val isMedia = type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK != 0
                Timber.i("FGS check after play start: foregroundServiceType=$type mediaPlayback=$isMedia")
            }
        }, 1_000L)
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        private fun ready(): SessionState.Ready? = useCases.observeSessionState().value as? SessionState.Ready

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            Timber.i("controller connected: ${controller.packageName}")
            return super.onConnect(session, controller)
        }

        /**
         * 사용자가 누른 미디어 키를 직접 처리한다(true 반환, play()로 바뀌지 않음, 출처 MEDIA_KEY).
         * PLAY는 재생 방향, PAUSE·STOP은 정지, PLAY_PAUSE·HEADSETHOOK은 재생(준비) 중이면 정지 아니면 재생,
         * 트랙 이동·탐색 키는 로그만 남기고 무시한다. 그 밖의 키는 false를 돌려 Media3 기본 처리에 넘긴다.
         * 미디어 알림 컨트롤러 이름으로 온 키(startService로 온 미디어 버튼 인텐트)는 false를 돌려 Media3 기본 처리에 맡긴다
         * (이어지는 transport play는 OTHER_CONTROLLER).
         */
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            val key = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) ?: return false
            val from = controllerInfo.packageName
            if (session.isMediaNotificationController(controllerInfo)) {
                // startService로 들어온 미디어 버튼 인텐트(미디어 알림 버튼, 또는 다른 앱이 exported 서비스에 보낸 위조 인텐트).
                // 사용자 키로 보지 않고 Media3 기본 처리(→ transport play, 출처 OTHER)에 맡긴다(적대적 리뷰 M2).
                if (key.action == KeyEvent.ACTION_DOWN) Timber.i("media button via service intent key=${key.keyCode} -> default handling")
                return false
            }
            val down = key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0
            if (down) Timber.i("media button from=$from key=${key.keyCode}")
            // 키를 직접 처리해(true 반환) 시간 창 없이 출처를 MEDIA_KEY로 확정한다(적대적 리뷰 M1).
            when (key.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY -> if (down) player.onMediaKeyPlay(from)
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> if (down) {
                    if (player.isActive) player.onMediaKeyPause(from) else player.onMediaKeyPlay(from)
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> if (down) player.onMediaKeyPause(from)
                KeyEvent.KEYCODE_MEDIA_STOP -> if (down) player.onMediaKeyStop(from)
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND ->
                    if (down) Timber.i("media button key=${key.keyCode} ignored (no track navigation)")
                else -> return false
            }
            return true
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(MediaItems.root(), params))

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            if (useCases.observeSessionState().value is SessionState.Loading) {
                val future = SettableFuture.create<LibraryResult<MediaItem>>()
                appScope.launch {
                    useCases.observeSessionState().first { it !is SessionState.Loading }
                    future.setFuture(onGetItem(session, browser, mediaId))
                }
                return future
            }
            val item = itemFor(mediaId)
            return Futures.immediateFuture(
                if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE),
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (useCases.observeSessionState().value is SessionState.Loading) {
                // 카탈로그 로딩 중이면 오류 대신 로딩이 끝난 뒤 응답한다(W5)
                val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
                appScope.launch {
                    useCases.observeSessionState().first { it !is SessionState.Loading }
                    future.setFuture(onGetChildren(session, browser, parentId, page, pageSize, params))
                }
                return future
            }
            val catalog = ready()?.catalog
                ?: return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_UNKNOWN))
            val children: List<MediaItem> = when (val node = MediaIds.parse(parentId)) {
                MediaNode.Root -> catalog.contents.map { MediaItems.content(it) }
                is MediaNode.ContentNode -> catalog.content(node.contentId)?.let { c ->
                    c.chapters.map { MediaItems.chapter(c, it) }
                }
                else -> null
            } ?: return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(MediaIds.page(children, page, pageSize)), params),
            )
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            val count = ready()?.catalog?.let { MediaIds.search(it, query).size } ?: 0
            Timber.i("media search from=${browser.packageName} query='$query' results=$count")
            session.notifySearchResultChanged(browser, query, count, params)
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val items = ready()?.catalog?.let { cat ->
                MediaIds.search(cat, query).map { (c, ch) -> MediaItems.chapter(c, ch) }
            } ?: emptyList()
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(MediaIds.page(items, page, pageSize)), params),
            )
        }

        /**
         * playFromMediaId / playFromSearch / setMediaItems. 요청 내용은 기록만 하고, 재생은 항상 현재 챕터다
         * (플레이어가 플레이리스트 변경을 무시하고 Play 명령만 게이팅한다).
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val desc = mediaItems.joinToString {
                val q = it.requestMetadata.searchQuery
                if (q != null) "search='$q'" else "mediaId=${it.mediaId}"
            }
            Timber.i("media request from=${controller.packageName}: $desc -> current chapter")
            val current = currentItem() ?: return Futures.immediateFailedFuture(UnsupportedOperationException("no content"))
            return Futures.immediateFuture(mutableListOf(current))
        }

        /**
         * 시스템 재생 재개 요청. 재생용(isForPlayback=true)이면 재개 항목을 거절한다. 표시용(false)이면 현재 챕터를 돌려준다.
         * Media3 1.11.1은 거절 뒤에도 Player.play()를 부르므로(성공·실패와 무관), 자동 재개로 학습 ON·재생이 일어나지 않게
         * 막는 것은 이 거절이 아니라 GatedPlayer가 거치는 출처 게이트(HandlePlayCommandUseCase)다(요구 1장).
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            Timber.i("playback resumption from=${controller.packageName} isForPlayback=$isForPlayback")
            if (isForPlayback) {
                Timber.i("playback resumption for playback refused (no resumption item; the following play() goes through the source gate)")
                return Futures.immediateFailedFuture(UnsupportedOperationException("auto resume disabled"))
            }
            val current = currentItem() ?: return Futures.immediateFailedFuture(UnsupportedOperationException("no content"))
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(listOf(current), 0, 0L))
        }

        private fun currentItem(): MediaItem? {
            val r = ready() ?: return null
            val s = useCases.getLearningState() ?: return null
            val content = s.contentId?.let { r.catalog.content(it) } ?: return null
            val chapter = content.chapters.firstOrNull { it.chapterId == s.currentChapterId }
                ?: content.chapters.firstOrNull() ?: return null
            // 플레이어가 무시하므로 URI는 자리표시(기본 onAddMediaItems 규칙: localConfiguration 필요)
            return MediaItems.chapter(content, chapter, ("voca://" + MediaIds.chapter(content.contentId, chapter.chapterId)).toUri())
        }

        private fun itemFor(mediaId: String): MediaItem? {
            val catalog = ready()?.catalog ?: return null
            return when (val node = MediaIds.parse(mediaId)) {
                MediaNode.Root -> MediaItems.root()
                is MediaNode.ContentNode -> catalog.content(node.contentId)?.let { MediaItems.content(it) }
                is MediaNode.ChapterNode -> catalog.content(node.contentId)?.let { c ->
                    c.chapters.firstOrNull { it.chapterId == node.chapterId }?.let { MediaItems.chapter(c, it) }
                }
                null -> null
            }
        }
    }
}
