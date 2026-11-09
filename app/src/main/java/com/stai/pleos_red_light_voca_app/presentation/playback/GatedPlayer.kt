package com.stai.pleos_red_light_voca_app.presentation.playback

import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.annotation.OptIn
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.stai.pleos_red_light_voca_app.core.entity.Chapter
import com.stai.pleos_red_light_voca_app.core.entity.Content
import com.stai.pleos_red_light_voca_app.domain.learning.PauseReason
import com.stai.pleos_red_light_voca_app.domain.learning.PlayTicket
import com.stai.pleos_red_light_voca_app.domain.learning.PlaybackEnd
import com.stai.pleos_red_light_voca_app.domain.learning.PlaybackReport
import com.stai.pleos_red_light_voca_app.domain.learning.PlayerControl
import com.stai.pleos_red_light_voca_app.domain.playback.PlayCommandResult
import com.stai.pleos_red_light_voca_app.domain.playback.PlaySource
import com.stai.pleos_red_light_voca_app.domain.speech.ChapterAudioResult
import com.stai.pleos_red_light_voca_app.domain.session.SessionState
import timber.log.Timber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * MediaSession에 노출하는 유일한 플레이어. 실제 ExoPlayer는 감춰 두고, 컨트롤러 명령을 여기서 게이팅한다(ADR-001).
 *
 * - transport Play 계열(onPlay, playFromMediaId, playFromSearch, 재생 재개, 미디어 알림 버튼)은 Media3가
 *   이 플레이어의 play()로 바꿔 부른다 → [handleSetPlayWhenReady] → 출처 분류([PlaySourceClassifier]).
 *   사용자가 누른 미디어 키는 서비스의 onMediaButtonEvent가 직접 처리해 [onMediaKeyPlay]·[onMediaKeyPause]·
 *   [onMediaKeyStop]으로 부른다(play()로 바뀌지 않음, 출처 MEDIA_KEY).
 *   두 경로 모두 출처 게이트·학습 세션의 재생 허가(HandlePlayCommandUseCase)를 거친다.
 *   허락(Play)일 때만 챕터 WAV를 준비해 seekTo(0) 후 재생한다. 그 밖에는 재생하지 않는다.
 * - 플레이리스트는 항상 "현재 챕터 1개"로 보인다. 컨트롤러의 setMediaItems·prepare·seek는 무시한다
 *   (playFromMediaId로 다른 챕터를 골라도 현재 챕터를 재생한다).
 * - 앱 코드가 스스로 재생을 시작하는 경로는 없다. ExoPlayer.play()는 컨트롤러 Play 명령 처리 안에서만 부른다.
 * - 오디오 포커스는 ExoPlayer(handleAudioFocus=true)가 다룬다. 상실 시 멈추고 재시도·자동 재개하지 않는다.
 *
 * 모든 메서드는 메인 스레드에서 호출된다. 학습 세션은 [useCases]의 domain UseCase로만 다룬다.
 *
 * @param scope 앱(프로세스) 단위 메인 스레드 스코프. Play 명령 처리(콜드 스타트 대기 포함)와 챕터 음성 준비를 여기서 한다.
 */
// SimpleBasePlayer는 Media3 UnstableApi. 버전 고정(1.11.1)으로 사용하고 업그레이드 시 확인한다.
@OptIn(UnstableApi::class)
class GatedPlayer(
    looper: Looper,
    private val exo: ExoPlayer,
    private val useCases: GatedPlayerUseCases,
    private val scope: CoroutineScope,
    private val debuggableBuild: Boolean,
    /** 이 앱의 패키지 이름. 화면 "듣기" 버튼 컨트롤러(APP_UI) 확인용. */
    private val ownPackage: String? = null,
    private val onPlaybackStartedHook: () -> Unit = {},
) : SimpleBasePlayer(looper), PlayerControl {

    /** 서비스가 세션을 만든 뒤 넣는다. 명령을 보낸 컨트롤러(packageName) 확인용. */
    var session: MediaSession? = null

    /** Play 허가 후 음원 병합·준비 중인 티켓 */
    private var pendingTicket: PlayTicket? = null
    private var pendingJob: Job? = null

    /** ExoPlayer에 실려 재생 중인 티켓 */
    private var exoTicket: PlayTicket? = null
    private var startedReported = false

    /** ExoPlayer는 마지막 playWhenReady 변경 이유를 조회하는 API가 없어 리스너에서 기억한다. */
    private var lastPlayWhenReadyReason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST

    private val exoListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            invalidateState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val t = exoTicket ?: return
            if (isPlaying && !startedReported) {
                startedReported = true
                useCases.reportPlayback(PlaybackReport.Started(t))
                onPlaybackStartedHook()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_ENDED) return
            val t = exoTicket ?: return
            if (!PlaybackRules.isEndedForTicket(exo.currentMediaItem?.localConfiguration?.tag, t)) {
                Timber.i("STATE_ENDED for stale item ignored")
                return
            }
            exoTicket = null
            Timber.i("player STATE_ENDED ${t.contentId}/${t.chapterId}")
            useCases.reportPlayback(PlaybackReport.Ended(t))
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            lastPlayWhenReadyReason = reason
            if (PlaybackRules.onPlayWhenReadyChanged(playWhenReady, reason) == FocusAction.HALT_AND_REPORT) {
                // 영구 상실(AUDIOFOCUS_LOSS): Pleos 가이드대로 stop 처리, 재시도 없음
                interrupt(PlaybackEnd.FOCUS_LOSS, detail = "audio focus lost (permanent)")
            }
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            if (PlaybackRules.onSuppressionReason(playbackSuppressionReason) == FocusAction.HALT_AND_REPORT) {
                // 일시 상실: ExoPlayer는 포커스를 되찾으면 자동 재개하므로 여기서 멈춰(interrupt → exo.stop()) 자동 재개를 막는다
                interrupt(PlaybackEnd.FOCUS_LOSS, detail = "audio focus lost (transient), no auto-resume")
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            interrupt(PlaybackEnd.ERROR, detail = "player error ${error.errorCodeName}: ${error.message}")
        }
    }

    /** 컨트롤러 Play 명령 처리 안에서만 쓰는 ExoPlayer 시작 대상 */
    private val exoTarget = object : StartTarget<MediaItem> {
        override fun setMediaItem(item: MediaItem) = exo.setMediaItem(item)
        override fun prepare() = exo.prepare()
        override fun seekTo(positionMs: Long) = exo.seekTo(positionMs)
        override fun play() = exo.play()
    }

    init {
        exo.addListener(exoListener)
    }

    private fun requester(): String? = session?.controllerForCurrentRequest?.packageName

    /** 컨트롤러 상태(현재 챕터 등)가 바뀌면 서비스가 부른다. */
    fun refresh() {
        invalidateState()
    }

    // ---- Media3 상태 ----

    override fun getState(): State {
        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_PREPARE,
                Player.COMMAND_SET_MEDIA_ITEM,
                Player.COMMAND_CHANGE_MEDIA_ITEMS,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_AUDIO_ATTRIBUTES,
                Player.COMMAND_RELEASE,
            )
            .build()
        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setAudioAttributes(exo.audioAttributes)
        val shown = displayed()
        if (shown == null) {
            return builder
                .setPlaylist(emptyList())
                .setPlaybackState(Player.STATE_IDLE)
                .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .build()
        }
        val (content, chapter, ticket) = shown
        val loaded = ticket != null && ticket == exoTicket
        val preparing = ticket != null && ticket == pendingTicket
        val durationUs = if (loaded && exo.duration != C.TIME_UNSET) exo.duration * 1000 else C.TIME_UNSET
        val item = MediaItemData.Builder(MediaIds.chapter(content.contentId, chapter.chapterId))
            .setMediaItem(MediaItems.chapter(content, chapter))
            .setIsSeekable(false)
            .setDurationUs(durationUs)
            .build()
        builder.setPlaylist(listOf(item)).setCurrentMediaItemIndex(0)
        when {
            loaded -> {
                val state = exo.playbackState
                builder
                    .setPlaybackState(state)
                    .setPlayWhenReady(exo.playWhenReady, lastPlayWhenReadyReason)
                    .setPlaybackSuppressionReason(exo.playbackSuppressionReason)
                    .setIsLoading(exo.isLoading && state != Player.STATE_IDLE && state != Player.STATE_ENDED)
                    .setPlayerError(if (state == Player.STATE_IDLE) exo.playerError else null)
                    .setContentPositionMs { exo.currentPosition }
            }
            preparing -> builder
                .setPlaybackState(Player.STATE_BUFFERING)
                .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setContentPositionMs(0)
            else -> builder
                .setPlaybackState(Player.STATE_IDLE)
                .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setContentPositionMs(0)
        }
        return builder.build()
    }

    /** 세션에 보여 줄 챕터: 재생(준비) 중 티켓 > 컨트롤러의 다음 챕터 */
    private fun displayed(): Triple<Content, Chapter, PlayTicket?>? {
        val ready = useCases.observeSessionState().value as? SessionState.Ready ?: return null
        val ticket = exoTicket ?: pendingTicket
        if (ticket != null) {
            lookup(ready, ticket.contentId, ticket.chapterId)?.let { return Triple(it.first, it.second, ticket) }
        }
        val s = useCases.getLearningState() ?: return null
        val contentId = s.contentId ?: return null
        val chapterId = s.currentChapterId ?: return null
        return lookup(ready, contentId, chapterId)?.let { Triple(it.first, it.second, null) }
    }

    private fun lookup(ready: SessionState.Ready, contentId: String, chapterId: String): Pair<Content, Chapter>? {
        val content = ready.catalog.content(contentId) ?: return null
        val chapter = content.chapters.firstOrNull { it.chapterId == chapterId } ?: return null
        return content to chapter
    }

    // ---- 컨트롤러 명령 ----

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        val info = session?.controllerForCurrentRequest
        val from = info?.packageName
        if (playWhenReady) handlePlay(classify(info), from) else onPauseCommand(from)
        return Futures.immediateVoidFuture()
    }

    /** 컨트롤러 명령(transport play)의 출처. 미디어 키는 [onMediaKeyPlay]로 따로 들어온다. */
    private fun classify(info: MediaSession.ControllerInfo?): PlaySource =
        PlaySourceClassifier.classify(
            packageName = info?.packageName,
            hint = { key -> info?.connectionHints?.getBoolean(key, false) == true },
            debuggableBuild = debuggableBuild,
            ownPackage = ownPackage,
        )

    /** 재생(준비) 중인지. PLAY_PAUSE 키의 방향 판단용. */
    val isActive: Boolean get() = pendingTicket != null || exoTicket != null

    /** 세션 콜백 onMediaButtonEvent가 직접 처리한 사용자 미디어 키(PLAY 방향). */
    fun onMediaKeyPlay(from: String?) = handlePlay(PlaySource.MEDIA_KEY, from)

    /** 세션 콜백 onMediaButtonEvent가 직접 처리한 사용자 미디어 키(PAUSE 방향). */
    fun onMediaKeyPause(from: String?) = onPauseCommand(from)

    /** 세션 콜백 onMediaButtonEvent가 직접 처리한 STOP 키. */
    fun onMediaKeyStop(from: String?) = stopCommand(from)

    /**
     * Play 명령 처리(출처 게이트·콜드 스타트 대기·학습 세션 판정)는 domain UseCase가 하고, 여기서는 허가된 재생만 시작한다.
     * UNDISPATCHED: 앱·음원 확인이 끝난 상태면 UseCase가 중단 없이 끝나 이 호출 안에서 판정·재생 준비까지 한다
     * (Media3 명령 처리와 같은 호출). 콜드 스타트 대기만 [scope]에서 이어진다.
     */
    private fun handlePlay(source: PlaySource, from: String?) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            when (val result = useCases.handlePlayCommand(source, from)) {
                is PlayCommandResult.Play -> {
                    start(result.ticket, from)
                    invalidateState()
                }
                PlayCommandResult.Forwarded -> invalidateState()
                PlayCommandResult.Ignored -> Unit
            }
        }
    }

    private fun onPauseCommand(from: String?) {
        useCases.reportPauseCommand(from)
        haltInternal()
        Timber.i("player stopped by pause from=$from (no auto-resume)")
    }

    private fun stopCommand(from: String?) {
        useCases.reportStopCommand(from)
        haltInternal()
        Timber.i("player stopped by stop from=$from")
    }

    override fun handleStop(): ListenableFuture<*> {
        stopCommand(requester())
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = ignored("prepare")

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> = ignored("setMediaItems ${mediaItems.map { it.mediaId }}")

    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> =
        ignored("addMediaItems ${mediaItems.map { it.mediaId }}")

    override fun handleReplaceMediaItems(
        fromIndex: Int,
        toIndex: Int,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<*> = ignored("replaceMediaItems")

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> = ignored("removeMediaItems")

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> =
        ignored("moveMediaItems")

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    private fun ignored(what: String): ListenableFuture<*> {
        Timber.i("media $what from=${requester()} ignored (playlist is fixed to current chapter)")
        return Futures.immediateVoidFuture()
    }

    // ---- 재생 ----

    /** Play 허가를 받은 컨트롤러 명령의 처리. 음원 병합(캐시 없으면) → 아이템 설정 → seekTo(0) → 재생. */
    private fun start(ticket: PlayTicket, from: String?) {
        val ready = useCases.observeSessionState().value as? SessionState.Ready ?: return
        val found = lookup(ready, ticket.contentId, ticket.chapterId)
        if (found == null) {
            useCases.reportPlayback(PlaybackReport.Interrupted(ticket, PlaybackEnd.ERROR))
            return
        }
        val (content, chapter) = found
        haltInternal()
        pendingTicket = ticket
        Timber.i("play approved ${ticket.contentId}/${ticket.chapterId} (controller=$from): preparing audio")
        pendingJob = scope.launch {
            val audio = try {
                useCases.prepareChapterAudio(ticket.contentId, chapter)
            } catch (e: CancellationException) {
                // 정지 요청(haltInternal)이 아닌 취소면 시작 실패로 보고해 PREPARING에 머물지 않게 한다
                if (pendingTicket == ticket) {
                    pendingTicket = null
                    useCases.reportPlayback(PlaybackReport.Interrupted(ticket, PlaybackEnd.ERROR))
                    invalidateState()
                }
                throw e
            }
            if (pendingTicket != ticket || !useCases.validatePlayTicket(ticket)) {
                Timber.i("play cancelled before start ${ticket.contentId}/${ticket.chapterId}")
                return@launch
            }
            when (audio) {
                is ChapterAudioResult.Failed -> {
                    pendingTicket = null
                    Timber.w("chapter audio failed: ${audio.message}")
                    useCases.reportPlayback(PlaybackReport.Interrupted(ticket, PlaybackEnd.ERROR))
                }
                is ChapterAudioResult.Ready -> {
                    startedReported = false
                    // pendingTicket을 유지한 채 시작해 세션이 BUFFERING으로 보이게 한다(IDLE 깜빡임 방지).
                    // seekTo(0) 포함. 컨트롤러 Play 명령 처리의 연속이며 앱이 스스로 시작하는 재생이 아니다.
                    val item = MediaItems.chapter(content, chapter, Uri.fromFile(audio.wavFile), ticket)
                    PlaybackRules.startFromBeginning(exoTarget, item)
                    exoTicket = ticket
                    pendingTicket = null
                    Timber.i("play start ${ticket.contentId}/${ticket.chapterId} seekTo(0) file=${audio.wavFile.name}")
                    if (!exo.playWhenReady) {
                        // 포커스 요청이 즉시 거절된 경우: 시작하지 못한 것으로 보고(PREPARING에 머물지 않게)
                        interrupt(PlaybackEnd.FOCUS_LOSS, detail = "audio focus not granted at start")
                    }
                }
            }
            invalidateState()
        }
        invalidateState()
    }

    /** 학습 세션의 정지 요청(출발·기어 이탈·데이터 오류·준비 중 서행·학습 종료). 준비 중이면 시작하지 않는다. */
    override fun pause(reason: PauseReason) {
        Timber.i("player stop requested by controller: $reason")
        haltInternal()
    }

    /** 서비스 종료 시: 진행 중 재생은 끝까지 못 간 것으로 보고한다. */
    fun onServiceEnding() {
        val t = exoTicket ?: pendingTicket
        haltInternal()
        if (t != null) useCases.reportPlayback(PlaybackReport.Interrupted(t, PlaybackEnd.ERROR))
    }

    /** 정지는 항상 stop(): 오디오 포커스를 반납하고 세션에 쥔 채 남지 않게 한다. 재생은 늘 처음부터라 위치를 잃어도 된다. */
    private fun interrupt(reason: PlaybackEnd, detail: String) {
        val t = exoTicket ?: return
        exoTicket = null
        Timber.i("playback interrupted: $detail")
        exo.stop()
        useCases.reportPlayback(PlaybackReport.Interrupted(t, reason))
        invalidateState()
    }

    /** 준비 취소 + 재생 정지. 티켓을 먼저 비워 리스너가 중복 보고하지 않게 한다. */
    private fun haltInternal() {
        pendingJob?.cancel()
        pendingJob = null
        pendingTicket = null
        exoTicket = null
        if (exo.playbackState != Player.STATE_IDLE || exo.playWhenReady) exo.stop()
        invalidateState()
    }

    fun releaseInternal() {
        exo.removeListener(exoListener)
    }
}
