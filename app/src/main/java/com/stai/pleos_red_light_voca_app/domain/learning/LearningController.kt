package com.stai.pleos_red_light_voca_app.domain.learning

import com.stai.pleos_red_light_voca_app.core.common.LearningConfig
import com.stai.pleos_red_light_voca_app.core.entity.Catalog
import com.stai.pleos_red_light_voca_app.core.entity.Chapter
import com.stai.pleos_red_light_voca_app.core.entity.Content
import com.stai.pleos_red_light_voca_app.domain.gate.GatePhase
import com.stai.pleos_red_light_voca_app.domain.gate.HaltReason
import com.stai.pleos_red_light_voca_app.domain.gate.StopGate
import com.stai.pleos_red_light_voca_app.domain.repository.ProgressRepository
import com.stai.pleos_red_light_voca_app.core.common.Logger
import com.stai.pleos_red_light_voca_app.core.common.MonotonicClock
import com.stai.pleos_red_light_voca_app.core.common.Scheduler
import com.stai.pleos_red_light_voca_app.core.entity.SignalBlock
import com.stai.pleos_red_light_voca_app.core.entity.VehicleEvent
import com.stai.pleos_red_light_voca_app.core.entity.VehicleSignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 학습 세션·정차 판정·재생 허가·진도를 묶는 상태 머신. Android 의존 없음.
 *
 * 스레드: 모든 공개 메서드는 같은 스레드(메인)에서 호출한다. [scheduler] 콜백도 같은 스레드여야 한다.
 * 진도 저장만 [scope]에서 비동기로 한다.
 *
 * 재생 시작 경로는 없다. [onPlayCommand]는 "재생해도 된다"는 결정([PlayDecision.Play])만 돌려주고,
 * 실제 seekTo(0)·play()는 MediaSession 명령 처리 쪽이 한다.
 */
class LearningController(
    private val catalog: Catalog,
    private val progressRepository: ProgressRepository,
    private val player: PlayerControl,
    clock: MonotonicClock,
    scheduler: Scheduler,
    private val scope: CoroutineScope,
    private val config: LearningConfig = LearningConfig(),
    private val logger: Logger = Logger.NONE,
) {
    private val _state = MutableStateFlow(LearningState())
    val state: StateFlow<LearningState> = _state.asStateFlow()

    private val gate = StopGate(config, clock, scheduler) { old, new ->
        logger.i("gate $old -> $new")
        _state.update { it.copy(gatePhase = new) }
    }

    /** 콘텐츠별 마지막 완료 챕터 (메모리 사본) */
    private val completed = mutableMapOf<String, String>()
    private val blocked = linkedMapOf<VehicleSignal, SignalBlock>()
    private var activeTicket: PlayTicket? = null
    private var nextTicketId = 1L

    /**
     * 진도 저장을 하나씩 실행한다(Mutex는 FIFO). 호출이 한 스레드(메인)에서 일어나고 launch가 즉시(Main.immediate)
     * Mutex 대기에 들어가는 전제에서 호출 순서대로 실행된다. 다른 디스패처로 launch가 늦게 시작하면 순서는 보장되지 않는다.
     */
    private val persistMutex = Mutex()

    /**
     * 저장 실패(IOException 등)는 잡아서 로그로 남긴다(앱을 죽이지 않음). 메모리 진도는 유지되므로 다음 저장에서 다시 쓴다.
     * [done]은 저장이 실제로 끝났을 때의 로그 문구.
     */
    private fun persist(done: String, block: suspend () -> Unit) {
        scope.launch {
            persistMutex.withLock {
                try {
                    block()
                    logger.i(done)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.i("progress save FAILED ($done): ${e.javaClass.simpleName} ${e.message}")
                }
            }
        }
    }

    /**
     * 앱·서비스 시작 시 1회. 진도만 복원하고 학습은 OFF로 둔다.
     */
    suspend fun restore() {
        val progress = progressRepository.load()
        completed.clear()
        completed.putAll(progress.lastCompletedChapterIds)
        val selected = progress.activeContentId?.takeIf { catalog.content(it) != null }
            ?: catalog.contents.firstOrNull()?.contentId
        _state.update { it.copy(learningOn = false) }
        selectInternal(selected)
        logger.i("restore: learning OFF, content=$selected, progress=${progress.lastCompletedChapterIds}")
    }

    /** 학습 OFF일 때만 콘텐츠를 바꿀 수 있다. */
    fun selectContent(contentId: String): Boolean {
        if (_state.value.learningOn) return false
        if (catalog.content(contentId) == null) return false
        selectInternal(contentId)
        return true
    }

    /**
     * 학습 시작(화면 버튼). 재생은 시작하지 않는다. 주행 조건부터 다시 확인한다.
     * 콘텐츠 음원 확인은 이 호출 전에 `AppSession.startLearning`(화면)과 `HandlePlayCommandUseCase`(Play 명령)가 한다.
     * 이미 모두 끝낸 콘텐츠면 진도를 지우고 첫 챕터부터 시작한다.
     */
    fun startLearning(contentId: String? = _state.value.contentId): StartResult {
        disabledReason()?.let {
            logger.i("startLearning refused: disabled $it")
            return StartResult.Disabled(it)
        }
        if (_state.value.learningOn) return StartResult.AlreadyOn
        val content = contentId?.let { catalog.content(it) } ?: return StartResult.UnknownContent
        if (content.chapters.isEmpty()) return StartResult.UnknownContent
        val clearProgress = nextChapter(content) == null
        if (clearProgress) {
            completed.remove(content.contentId)
            logger.i("progress cleared (restart completed content ${content.contentId})")
        }
        selectInternal(content.contentId)
        persist("progress saved: active=${content.contentId}${if (clearProgress) ", cleared" else ""}") {
            if (clearProgress) {
                progressRepository.restartContent(content.contentId)
            } else {
                progressRepository.saveActiveContent(content.contentId)
            }
        }
        _state.update { it.copy(learningOn = true, sessionCompleted = false) }
        logger.i("learning ON content=${content.contentId} chapter=${_state.value.currentChapterId}")
        val v = _state.value.vehicle
        gate.activate(v.speedKmh, v.gear)
        return StartResult.Started
    }

    /** 학습 종료(화면 버튼). 재생 중이면 멈춘다. */
    fun stopLearning() {
        if (!_state.value.learningOn) return
        turnOff(PlaybackEnd.LEARNING_STOPPED, sessionCompleted = false)
    }

    fun onVehicleEvent(event: VehicleEvent) {
        logger.i("vehicle $event")
        when (event) {
            is VehicleEvent.SpeedChanged -> {
                unblock(VehicleSignal.SPEED)
                _state.update { it.copy(vehicle = it.vehicle.copy(speedKmh = event.speedKmh, speedFailure = null)) }
                onSample()
            }
            is VehicleEvent.GearChanged -> {
                unblock(VehicleSignal.GEAR)
                _state.update { it.copy(vehicle = it.vehicle.copy(gear = event.gear, gearFailure = null)) }
                onSample()
            }
            is VehicleEvent.SignalFailed -> {
                _state.update {
                    val v = when (event.signal) {
                        VehicleSignal.SPEED -> it.vehicle.copy(speedKmh = null, speedFailure = event.failure)
                        VehicleSignal.GEAR -> it.vehicle.copy(gear = null, gearFailure = event.failure)
                    }
                    it.copy(vehicle = v)
                }
                haltPlayback(PlaybackEnd.VEHICLE_DATA_ERROR)
                gate.onDataError()
            }
            is VehicleEvent.SignalBlocked -> {
                blocked[event.signal] = event.block
                _state.update {
                    val v = when (event.signal) {
                        VehicleSignal.SPEED -> it.vehicle.copy(speedKmh = null)
                        VehicleSignal.GEAR -> it.vehicle.copy(gear = null)
                    }
                    it.copy(vehicle = v, disabledReason = disabledReason())
                }
                logger.i("learning disabled: $blocked")
                if (_state.value.learningOn) turnOff(PlaybackEnd.LEARNING_STOPPED, sessionCompleted = false)
            }
        }
    }

    /**
     * 시스템 미디어 Play 계열 명령(onPlay, PLAY_PAUSE 토글로 재생 방향, playFromMediaId, playFromSearch,
     * 재생 재개 요청). [source]는 controllerInfo.packageName(로그용).
     */
    fun onPlayCommand(source: String?): PlayDecision {
        val decision = decidePlay()
        logger.i("media PLAY from=$source -> $decision")
        return decision
    }

    private fun decidePlay(): PlayDecision {
        if (disabledReason() != null) return PlayDecision.Ignored(IgnoreReason.DISABLED)
        val s = _state.value
        if (!s.learningOn) {
            return when (startLearning()) {
                StartResult.Started -> PlayDecision.LearningEnabledOnly
                is StartResult.Disabled -> PlayDecision.Ignored(IgnoreReason.DISABLED)
                StartResult.AlreadyOn, StartResult.UnknownContent -> PlayDecision.Ignored(IgnoreReason.NO_CONTENT)
            }
        }
        if (s.playback != PlaybackPhase.IDLE) return PlayDecision.Ignored(IgnoreReason.ALREADY_PLAYING)
        when (gate.phase) {
            GatePhase.ARMED -> Unit
            GatePhase.ARMED_CREEP -> return PlayDecision.Ignored(IgnoreReason.CREEPING)
            GatePhase.USED -> return PlayDecision.Ignored(IgnoreReason.STOP_ALREADY_USED)
            else -> return PlayDecision.Ignored(IgnoreReason.NOT_ARMED)
        }
        val content = s.contentId?.let { catalog.content(it) }
            ?: return PlayDecision.Ignored(IgnoreReason.NO_CONTENT)
        val chapter = nextChapter(content) ?: return PlayDecision.Ignored(IgnoreReason.NO_CONTENT)
        // 오디오 요청 전에 이번 정차를 잠근다(완료·중단·실패 모두 재시도 없음)
        gate.consume()
        val ticket = PlayTicket(nextTicketId++, content.contentId, chapter.chapterId)
        activeTicket = ticket
        _state.update { it.copy(playback = PlaybackPhase.PREPARING) }
        return PlayDecision.Play(ticket)
    }

    /** Pause 명령. 플레이어 정지는 MediaSession이 그대로 수행한다. 자동 재개 없음. */
    fun onPauseCommand(source: String?) {
        logger.i("media PAUSE from=$source")
        endPlayback(PlaybackEnd.USER_PAUSE)
    }

    /** Stop 명령. 플레이어 정지는 MediaSession이 그대로 수행한다. 학습 ON/OFF는 바꾸지 않는다. */
    fun onStopCommand(source: String?) {
        logger.i("media STOP from=$source")
        endPlayback(PlaybackEnd.USER_STOP)
    }

    /** 실제 play() 직전 확인. 준비 중 출발·기어 이탈 등으로 취소됐으면 false. */
    fun isTicketValid(ticket: PlayTicket): Boolean =
        ticket == activeTicket && _state.value.playback != PlaybackPhase.IDLE

    fun onPlaybackStarted(ticket: PlayTicket) {
        if (!isTicketValid(ticket)) {
            logger.i("playback started for stale ticket $ticket (ignored)")
            return
        }
        logger.i("playback started ${ticket.contentId}/${ticket.chapterId}")
        _state.update { it.copy(playback = PlaybackPhase.PLAYING) }
    }

    /** Player.STATE_ENDED. 이때만 챕터 완료·진도 저장. */
    fun onPlaybackEnded(ticket: PlayTicket) {
        if (!isTicketValid(ticket)) {
            logger.i("playback ended for stale ticket $ticket (ignored)")
            return
        }
        activeTicket = null
        completed[ticket.contentId] = ticket.chapterId
        persist("progress saved: ${ticket.contentId}/${ticket.chapterId} completed") {
            progressRepository.saveCompleted(ticket.contentId, ticket.chapterId)
        }
        logger.i("playback COMPLETED ${ticket.contentId}/${ticket.chapterId}; saving progress")
        _state.update { it.copy(playback = PlaybackPhase.IDLE, lastPlaybackEnd = PlaybackEnd.COMPLETED) }
        refreshProgressState()
        val content = catalog.content(ticket.contentId)
        if (content != null && nextChapter(content) == null) {
            logger.i("last chapter completed: session ended")
            turnOff(PlaybackEnd.COMPLETED, sessionCompleted = true)
        }
    }

    /** 재생이 끝까지 가지 못하고 멈춤(포커스 상실, 오류, 서비스 종료 등). 진도는 바뀌지 않는다. */
    fun onPlaybackInterrupted(ticket: PlayTicket, reason: PlaybackEnd) {
        if (!isTicketValid(ticket)) return
        logger.i("playback interrupted ${ticket.contentId}/${ticket.chapterId}: $reason")
        endPlayback(reason)
    }

    // ---- 내부 ----

    private fun onSample() {
        val v = _state.value.vehicle
        when (gate.onSample(v.speedKmh, v.gear)) {
            HaltReason.MOVING -> haltPlayback(PlaybackEnd.MOVING)
            HaltReason.GEAR_NOT_DRIVE -> haltPlayback(PlaybackEnd.GEAR_NOT_DRIVE)
            null -> {
                // Play 허가 뒤 준비(음원 병합) 중에 서행 (0.5, 1]이 시작되면 "새 재생 금지"이므로 시작하지 않는다.
                // 정차 시도는 이미 썼다(C4). 재생 중(PLAYING)의 서행은 1 km/h 초과 전까지 계속한다.
                val speed = v.speedKmh
                if (_state.value.playback == PlaybackPhase.PREPARING && speed != null && speed > config.stopSpeedKmh) {
                    haltPlayback(PlaybackEnd.MOVING)
                }
            }
        }
    }

    /** 컨트롤러가 일으키는 정지: 상태를 먼저 IDLE로 바꾸고 플레이어에 정지를 요청한다. */
    private fun haltPlayback(reason: PlaybackEnd) {
        if (_state.value.playback == PlaybackPhase.IDLE) return
        endPlayback(reason)
        logger.i("pause requested: $reason")
        player.pause(reason)
    }

    private fun endPlayback(reason: PlaybackEnd) {
        if (_state.value.playback == PlaybackPhase.IDLE) return
        val t = activeTicket
        activeTicket = null
        logger.i("playback end ${t?.contentId}/${t?.chapterId}: $reason (progress unchanged)")
        _state.update { it.copy(playback = PlaybackPhase.IDLE, lastPlaybackEnd = reason) }
    }

    private fun turnOff(reason: PlaybackEnd, sessionCompleted: Boolean) {
        haltPlayback(reason)
        gate.deactivate()
        _state.update { it.copy(learningOn = false, sessionCompleted = sessionCompleted) }
        logger.i("learning OFF ($reason)")
    }

    private fun unblock(signal: VehicleSignal) {
        if (blocked.remove(signal) != null) {
            logger.i("signal $signal available again")
            _state.update { it.copy(disabledReason = disabledReason()) }
        }
    }

    private fun disabledReason(): DisabledReason? =
        if (blocked.isEmpty()) null else DisabledReason(blocked.toMap())

    private fun nextChapter(content: Content): Chapter? {
        val last = completed[content.contentId] ?: return content.chapters.firstOrNull()
        val idx = content.indexOf(last)
        return content.chapters.getOrNull(idx + 1) // 알 수 없는 ID(-1)면 첫 챕터
    }

    private fun selectInternal(contentId: String?) {
        _state.update { it.copy(contentId = contentId) }
        refreshProgressState()
    }

    private fun completedCount(content: Content): Int {
        val next = nextChapter(content)
        return if (next == null) content.chapters.size else content.indexOf(next.chapterId)
    }

    private fun refreshProgressState() {
        val byContent = catalog.contents.associate { it.contentId to completedCount(it) }
        val content = _state.value.contentId?.let { catalog.content(it) }
        if (content == null) {
            _state.update {
                it.copy(currentChapterId = null, completedChapters = 0, totalChapters = 0, completedByContent = byContent)
            }
            return
        }
        val next = nextChapter(content)
        _state.update {
            it.copy(
                currentChapterId = next?.chapterId,
                completedChapters = byContent.getValue(content.contentId),
                totalChapters = content.chapters.size,
                completedByContent = byContent,
            )
        }
    }
}
