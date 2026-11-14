package com.stai.pleos_red_light_voca_app.domain.learning

import com.stai.pleos_red_light_voca_app.core.entity.Catalog
import com.stai.pleos_red_light_voca_app.core.entity.Chapter
import com.stai.pleos_red_light_voca_app.core.entity.Content
import com.stai.pleos_red_light_voca_app.domain.gate.GatePhase
import com.stai.pleos_red_light_voca_app.core.entity.Progress
import com.stai.pleos_red_light_voca_app.domain.repository.ProgressRepository
import com.stai.pleos_red_light_voca_app.core.common.CoroutineScheduler
import com.stai.pleos_red_light_voca_app.core.common.Logger
import com.stai.pleos_red_light_voca_app.core.common.MonotonicClock
import com.stai.pleos_red_light_voca_app.core.entity.Gear
import com.stai.pleos_red_light_voca_app.core.entity.SignalBlock
import com.stai.pleos_red_light_voca_app.core.entity.SignalFailure
import com.stai.pleos_red_light_voca_app.core.entity.VehicleEvent
import com.stai.pleos_red_light_voca_app.core.entity.VehicleSignal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeProgressRepository(initial: Progress = Progress()) : ProgressRepository {
    var progress = initial
    val savedCompleted = mutableListOf<Pair<String, String>>()
    /** 저장 호출이 끝난 순서 기록(순서 테스트용) */
    val calls = mutableListOf<String>()
    /** 호출 이름별 지연(ms). 늦게 끝나는 저장을 흉내 낸다. */
    val delays = mutableMapOf<String, Long>()
    /** true면 saveCompleted가 IOException을 던진다 */
    var failSaveCompleted = false
    override suspend fun load(): Progress = progress
    override suspend fun saveCompleted(contentId: String, chapterId: String) {
        delays["saveCompleted"]?.let { delay(it) }
        if (failSaveCompleted) throw java.io.IOException("disk full")
        calls += "saveCompleted"
        savedCompleted += contentId to chapterId
        progress = progress.copy(lastCompletedChapterIds = progress.lastCompletedChapterIds + (contentId to chapterId))
    }
    override suspend fun clear(contentId: String) {
        delays["clear"]?.let { delay(it) }
        calls += "clear"
        progress = progress.copy(lastCompletedChapterIds = progress.lastCompletedChapterIds - contentId)
    }
    override suspend fun restartContent(contentId: String) {
        delays["restartContent"]?.let { delay(it) }
        calls += "restartContent"
        progress = progress.copy(
            lastCompletedChapterIds = progress.lastCompletedChapterIds - contentId,
            activeContentId = contentId,
        )
    }
    override suspend fun saveActiveContent(contentId: String) {
        delays["saveActiveContent"]?.let { delay(it) }
        calls += "saveActiveContent"
        progress = progress.copy(activeContentId = contentId)
    }
}

private class FakePlayer : PlayerControl {
    val pauses = mutableListOf<PauseReason>()
    /** false(기본)면 서비스 플레이어가 없는 상태처럼 자동 재생을 받지 않는다. */
    var autoCapable = false
    val autoStarts = mutableListOf<PlayTicket>()
    override fun pause(reason: PauseReason) { pauses += reason }
    override val canStartAutomatically: Boolean get() = autoCapable
    override fun startAutomatically(ticket: PlayTicket) { autoStarts += ticket }
}

private val catalog = Catalog(
    listOf(
        Content("day1", "Day 1", (1..3).map { Chapter("d1-$it", "w$it", "noun", "뜻$it") }),
        Content("day2", "Day 2", (1..2).map { Chapter("d2-$it", "x$it", "verb", "뜻$it") }),
    ),
)

/** 받은 로그(i·w)를 [logs]에 순서대로 남긴다. */
private class RecordingLogger(private val logs: MutableList<String>) : Logger {
    override fun i(message: String) {
        logs += message
    }

    override fun w(message: String, t: Throwable?) {
        logs += message
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class Harness(val scope: TestScope, val repo: FakeProgressRepository = FakeProgressRepository()) {
    val player = FakePlayer()
    val logs = mutableListOf<String>()
    val controller = LearningController(
        catalog = catalog,
        progressRepository = repo,
        player = player,
        clock = MonotonicClock { scope.testScheduler.currentTime },
        scheduler = CoroutineScheduler(scope.backgroundScope),
        scope = scope.backgroundScope,
        logger = RecordingLogger(logs),
    )
    val state get() = controller.state.value

    fun advance(ms: Long) {
        scope.advanceTimeBy(ms)
        scope.runCurrent()
    }

    fun speed(kmh: Float) { controller.onVehicleEvent(VehicleEvent.SpeedChanged(kmh)); scope.runCurrent() }
    fun gear(g: Gear) { controller.onVehicleEvent(VehicleEvent.GearChanged(g)); scope.runCurrent() }

    /** D단 주행 3초 → 감지 활성 */
    fun driveAndArm() {
        gear(Gear.DRIVE)
        speed(30f)
        advance(3_000)
        assertEquals(GatePhase.DETECTING, state.gatePhase)
    }

    /** 0 km/h 3초 → ARMED */
    fun stop() {
        speed(0f)
        advance(3_000)
        assertEquals(GatePhase.ARMED, state.gatePhase)
    }

    fun play(): PlayDecision = controller.onPlayCommand("com.test.controller").also { scope.runCurrent() }

    fun playTicket(): PlayTicket {
        val d = play()
        assertTrue("expected Play but was $d", d is PlayDecision.Play)
        return (d as PlayDecision.Play).ticket
    }

    fun start() {
        assertEquals(StartResult.Started, controller.startLearning("day1"))
        scope.runCurrent()
    }

    fun complete(t: PlayTicket) {
        controller.onPlaybackStarted(t)
        controller.onPlaybackEnded(t)
        scope.runCurrent()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LearningControllerTest {

    private fun test(repo: FakeProgressRepository = FakeProgressRepository(), body: suspend Harness.() -> Unit) = runTest {
        val h = Harness(this, repo)
        h.controller.restore()
        h.body()
    }

    // ---- 학습 세션 ----

    @Test fun restore_keeps_learning_off_and_restores_progress() = test(
        FakeProgressRepository(Progress(mapOf("day1" to "d1-1"), activeContentId = "day1")),
    ) {
        assertFalse(state.learningOn)
        assertEquals("day1", state.contentId)
        assertEquals("d1-2", state.currentChapterId)
        assertEquals(1, state.completedChapters)
        assertEquals(3, state.totalChapters)
        assertEquals(GatePhase.INACTIVE, state.gatePhase)
    }

    @Test fun completed_by_content_covers_all_contents_after_restore() = test(
        FakeProgressRepository(Progress(mapOf("day1" to "d1-1", "day2" to "d2-2"), activeContentId = "day1")),
    ) {
        assertEquals(mapOf("day1" to 1, "day2" to 2), state.completedByContent)
    }

    @Test fun completed_by_content_defaults_to_zero_without_progress() = test {
        assertEquals(mapOf("day1" to 0, "day2" to 0), state.completedByContent)
    }

    @Test fun completed_by_content_updates_on_chapter_complete_and_restart_of_completed_content() = test(
        FakeProgressRepository(Progress(mapOf("day2" to "d2-2"), activeContentId = "day1")),
    ) {
        assertEquals(mapOf("day1" to 0, "day2" to 2), state.completedByContent)
        start(); driveAndArm(); stop()
        complete(playTicket())
        assertEquals(mapOf("day1" to 1, "day2" to 2), state.completedByContent)

        // 완료한 day2를 다시 시작(C6)하면 day2 진도만 0으로 돌아간다
        controller.stopLearning()
        assertTrue(controller.selectContent("day2"))
        assertEquals(StartResult.Started, controller.startLearning("day2"))
        scope.runCurrent()
        assertEquals(mapOf("day1" to 1, "day2" to 0), state.completedByContent)
        assertEquals(0, state.completedChapters)
    }

    @Test fun vehicle_events_without_learning_do_not_arm_or_play() = test {
        driveAndArmIgnored()
        assertTrue(player.pauses.isEmpty())
    }

    private fun Harness.driveAndArmIgnored() {
        gear(Gear.DRIVE); speed(30f); advance(5_000); speed(0f); advance(5_000)
        assertEquals(GatePhase.INACTIVE, state.gatePhase)
        assertFalse(state.learningOn)
    }

    @Test fun start_learning_while_stopped_does_not_play_and_arms_after_stop_hold() = test {
        gear(Gear.DRIVE); speed(0f)
        start()
        assertTrue(state.learningOn)
        assertEquals(PlaybackPhase.IDLE, state.playback)
        // 이미 정차 중이면 주행 확인 없이 정차 확인부터(사용자 결정 2026-11-09)
        assertEquals(GatePhase.STOP_CONFIRMING, state.gatePhase)
        advance(3_000)
        assertEquals(GatePhase.ARMED, state.gatePhase)
        // 정차가 확정돼도 재생은 시작하지 않는다
        advance(60_000)
        assertEquals(PlaybackPhase.IDLE, state.playback)
    }

    @Test fun start_while_driving_needs_full_hold_from_start() = test {
        gear(Gear.DRIVE); speed(40f)
        advance(60_000)
        start()
        advance(2_999)
        assertEquals(GatePhase.DRIVE_CONFIRMING, state.gatePhase)
        advance(1)
        assertEquals(GatePhase.DETECTING, state.gatePhase)
    }

    @Test fun play_while_learning_off_only_enables_learning() = test {
        gear(Gear.DRIVE); speed(0f)
        assertEquals(PlayDecision.LearningEnabledOnly, play())
        assertTrue(state.learningOn)
        assertEquals(PlaybackPhase.IDLE, state.playback)
        assertEquals(GatePhase.STOP_CONFIRMING, state.gatePhase)
        // 정차 확정(3초) 전 다시 Play → 재생 안 함
        assertEquals(PlayDecision.Ignored(IgnoreReason.NOT_ARMED), play())
        assertEquals(PlaybackPhase.IDLE, state.playback)
    }

    @Test fun play_when_not_armed_is_ignored_in_every_pre_armed_phase() = test {
        start()
        assertEquals(PlayDecision.Ignored(IgnoreReason.NOT_ARMED), play()) // WAITING_DRIVE
        gear(Gear.DRIVE); speed(30f)
        assertEquals(PlayDecision.Ignored(IgnoreReason.NOT_ARMED), play()) // DRIVE_CONFIRMING
        advance(3_000)
        assertEquals(PlayDecision.Ignored(IgnoreReason.NOT_ARMED), play()) // DETECTING
        speed(0f); advance(2_000)
        assertEquals(GatePhase.STOP_CONFIRMING, state.gatePhase)
        assertEquals(PlayDecision.Ignored(IgnoreReason.NOT_ARMED), play()) // STOP_CONFIRMING
        assertEquals(PlaybackPhase.IDLE, state.playback)
    }

    @Test fun stop_learning_pauses_playback() = test {
        start(); driveAndArm(); stop()
        val t = playTicket()
        controller.onPlaybackStarted(t)
        controller.stopLearning()
        assertFalse(state.learningOn)
        assertEquals(listOf(PlaybackEnd.LEARNING_STOPPED), player.pauses)
        assertEquals(GatePhase.INACTIVE, state.gatePhase)
        assertFalse(controller.isTicketValid(t))
    }

    // ---- 정상 흐름, 진도 ----

    @Test fun normal_stop_play_completes_chapter_and_saves_progress_without_auto_next() = test {
        start(); driveAndArm(); stop()
        val t = playTicket()
        assertEquals("d1-1", t.chapterId)
        assertEquals(PlaybackPhase.PREPARING, state.playback)
        assertEquals(GatePhase.USED, state.gatePhase) // 오디오 요청 전에 잠금
        complete(t)
        assertEquals(listOf("day1" to "d1-1"), repo.savedCompleted)
        assertEquals("d1-2", state.currentChapterId)
        assertEquals(PlaybackPhase.IDLE, state.playback)
        assertEquals(PlaybackEnd.COMPLETED, state.lastPlaybackEnd)
        assertTrue(state.learningOn)
        // 다음 챕터 자동 재생 없음: 같은 정차의 Play도 거부
        assertEquals(PlayDecision.Ignored(IgnoreReason.STOP_ALREADY_USED), play())
    }

    @Test fun progress_is_saved_only_on_state_ended() = test {
        start(); driveAndArm(); stop()
        val t = playTicket()
        controller.onPlaybackStarted(t)
        controller.onPlaybackInterrupted(t, PlaybackEnd.ERROR)
        runCurrentAll()
        assertTrue(repo.savedCompleted.isEmpty())
        assertEquals("d1-1", state.currentChapterId)
    }

    private fun Harness.runCurrentAll() = scope.runCurrent()

    @Test fun interrupted_at_99_percent_replays_same_chapter_from_start_at_next_stop() = test {
        start(); driveAndArm(); stop()
        val t1 = playTicket()
        controller.onPlaybackStarted(t1)
        advance(4_900) // 거의 끝까지 재생
        speed(5f) // 출발 → 즉시 pause
        assertEquals(listOf(PlaybackEnd.MOVING), player.pauses)
        // 늦게 도착한 STATE_ENDED는 무시(이미 중단 처리됨)
        controller.onPlaybackEnded(t1)
        runCurrentAll()
        assertTrue(repo.savedCompleted.isEmpty())
        assertEquals("d1-1", state.currentChapterId)
        // 다음 유효 정차
        driveAndArm(); stop()
        val t2 = playTicket()
        assertEquals("d1-1", t2.chapterId)
        assertNotEquals(t1, t2)
    }

    @Test fun last_chapter_completion_ends_session() = test(
        FakeProgressRepository(Progress(mapOf("day1" to "d1-2"), "day1")),
    ) {
        start(); driveAndArm(); stop()
        val t = playTicket()
        assertEquals("d1-3", t.chapterId)
        complete(t)
        assertFalse(state.learningOn)
        assertTrue(state.sessionCompleted)
        assertEquals(GatePhase.INACTIVE, state.gatePhase)
        assertNull(state.currentChapterId)
        assertEquals(3, state.completedChapters)
        assertEquals("day1" to "d1-3", repo.savedCompleted.last())
        assertTrue(player.pauses.isEmpty())
    }

    @Test fun starting_completed_content_restarts_from_first_chapter() = test(
        FakeProgressRepository(Progress(mapOf("day1" to "d1-3"), "day1")),
    ) {
        assertNull(state.currentChapterId)
        start()
        assertEquals("d1-1", state.currentChapterId)
        assertFalse(state.sessionCompleted)
        runCurrentAll()
        assertNull(repo.progress.lastCompletedChapterIds["day1"])
    }

    @Test fun restart_restores_progress_and_learning_off() = runTest {
        val repo = FakeProgressRepository()
        val first = Harness(this, repo)
        first.controller.restore()
        first.start(); first.driveAndArm(); first.stop()
        first.complete(first.playTicket())
        assertTrue(first.state.learningOn)
        // 앱·서비스 재시작
        val second = Harness(this, repo)
        second.controller.restore()
        assertFalse(second.state.learningOn)
        assertEquals("day1", second.state.contentId)
        assertEquals("d1-2", second.state.currentChapterId)
        assertEquals(GatePhase.INACTIVE, second.state.gatePhase)
    }

    @Test fun select_content_only_when_off() = test {
        assertTrue(controller.selectContent("day2"))
        assertEquals("day2", state.contentId)
        assertEquals(2, state.totalChapters)
        assertFalse(controller.selectContent("nope"))
        assertEquals(StartResult.Started, controller.startLearning())
        assertFalse(controller.selectContent("day1"))
        assertEquals(StartResult.AlreadyOn, controller.startLearning("day1"))
        assertEquals("day2", state.contentId)
    }

    // ---- 정차 판정 경계 ----

    @Test fun boundary_values() = test {
        start()
        gear(Gear.DRIVE)
        speed(7.9f); advance(10_000)
        assertEquals(GatePhase.WAITING_DRIVE, state.gatePhase)
        speed(8f); advance(3_000)
        assertEquals(GatePhase.DETECTING, state.gatePhase)
        speed(0.6f); advance(10_000)
        assertEquals(GatePhase.DETECTING, state.gatePhase)
        speed(0.5f); advance(2_999)
        assertEquals(PlayDecision.Ignored(IgnoreReason.NOT_ARMED), play())
        advance(1)
        assertTrue(play() is PlayDecision.Play)
    }

    @Test fun stop_shorter_than_three_seconds_is_not_a_stop() = test {
        start(); driveAndArm()
        speed(0f); advance(2_500)
        speed(3f) // 3초 전에 출발
        advance(1_000)
        assertEquals(GatePhase.DETECTING, state.gatePhase)
        assertEquals(PlayDecision.Ignored(IgnoreReason.NOT_ARMED), play())
        // 다시 정차하면 처음부터 3초
        speed(0f); advance(2_000)
        assertEquals(GatePhase.STOP_CONFIRMING, state.gatePhase)
        advance(1_000)
        assertEquals(GatePhase.ARMED, state.gatePhase)
    }

    @Test fun long_stop_allows_only_one_attempt() = test {
        start(); driveAndArm(); stop()
        complete(playTicket())
        advance(30 * 60_000L) // 30분 정차, 같은 값 미반복
        assertEquals(GatePhase.USED, state.gatePhase)
        assertEquals(PlayDecision.Ignored(IgnoreReason.STOP_ALREADY_USED), play())
    }

    @Test fun long_armed_stop_without_play_stays_armed() = test {
        start(); driveAndArm(); stop()
        advance(20 * 60_000L)
        assertEquals(GatePhase.ARMED, state.gatePhase)
        assertTrue(play() is PlayDecision.Play)
    }

    @Test fun creeping_blocks_new_playback_but_does_not_stop_current() = test {
        start(); driveAndArm(); stop()
        speed(0.8f)
        assertEquals(GatePhase.ARMED_CREEP, state.gatePhase)
        assertEquals(PlayDecision.Ignored(IgnoreReason.CREEPING), play())
        speed(0.4f)
        val t = playTicket()
        controller.onPlaybackStarted(t)
        speed(3.0f) // (0.5, 3] 서행: 재생 유지
        assertTrue(player.pauses.isEmpty())
        assertEquals(PlaybackPhase.PLAYING, state.playback)
        speed(3.1f) // 3 초과: 즉시 pause
        assertEquals(listOf(PlaybackEnd.MOVING), player.pauses)
        assertEquals(PlaybackPhase.IDLE, state.playback)
    }

    @Test fun short_slow_roll_after_play_does_not_rearm() = test {
        start(); driveAndArm(); stop()
        complete(playTicket())
        speed(0.8f); speed(0f); advance(5_000)
        speed(6f); advance(5_000) // 8 미만 서행
        speed(0f); advance(5_000)
        assertEquals(GatePhase.USED, state.gatePhase)
        assertEquals(PlayDecision.Ignored(IgnoreReason.STOP_ALREADY_USED), play())
        // 충분한 재주행 후 다음 정차에서는 재생
        speed(9f); advance(3_000); stop()
        assertEquals("d1-2", playTicket().chapterId)
    }

    @Test fun armed_stop_ends_without_play_when_moving_and_needs_redrive() = test {
        start(); driveAndArm(); stop()
        speed(5f)
        assertEquals(GatePhase.WAITING_DRIVE, state.gatePhase)
        speed(0f); advance(5_000)
        assertEquals(PlayDecision.Ignored(IgnoreReason.NOT_ARMED), play())
    }

    // ---- 기어 이탈, 데이터 오류 ----

    @Test fun gear_leave_pauses_and_resets_history() = test {
        start(); driveAndArm(); stop()
        val t = playTicket()
        controller.onPlaybackStarted(t)
        gear(Gear.OTHER)
        assertEquals(listOf(PlaybackEnd.GEAR_NOT_DRIVE), player.pauses)
        assertEquals(GatePhase.WAITING_DRIVE, state.gatePhase)
        gear(Gear.DRIVE)
        speed(0f); advance(5_000)
        assertEquals(GatePhase.WAITING_DRIVE, state.gatePhase) // 주행 이력 초기화됨
    }

    @Test fun gear_leave_cancels_pending_drive_timer() = test {
        start(); gear(Gear.DRIVE); speed(20f); advance(2_000)
        gear(Gear.UNKNOWN)
        gear(Gear.DRIVE)
        advance(1_500) // 이전 타이머가 살아 있었다면 여기서 확정됐을 것
        assertEquals(GatePhase.DRIVE_CONFIRMING, state.gatePhase)
        advance(1_500)
        assertEquals(GatePhase.DETECTING, state.gatePhase)
    }

    @Test fun gear_leave_while_preparing_invalidates_ticket() = test {
        start(); driveAndArm(); stop()
        val t = playTicket()
        gear(Gear.OTHER)
        assertFalse(controller.isTicketValid(t))
        assertEquals(listOf(PlaybackEnd.GEAR_NOT_DRIVE), player.pauses)
        controller.onPlaybackStarted(t) // 늦은 시작 보고 무시
        assertEquals(PlaybackPhase.IDLE, state.playback)
    }

    @Test fun data_error_pauses_resets_and_requires_redrive_after_recovery() = test {
        start(); driveAndArm(); stop()
        val t = playTicket()
        controller.onPlaybackStarted(t)
        controller.onVehicleEvent(VehicleEvent.SignalFailed(VehicleSignal.SPEED, SignalFailure.UNAVAILABLE))
        assertEquals(listOf(PlaybackEnd.VEHICLE_DATA_ERROR), player.pauses)
        assertEquals(GatePhase.WAITING_DRIVE, state.gatePhase)
        assertNull(state.vehicle.speedKmh)
        assertEquals(SignalFailure.UNAVAILABLE, state.vehicle.speedFailure)
        speed(0f); advance(10_000) // 복구됐지만 정차 값만
        assertEquals(GatePhase.WAITING_DRIVE, state.gatePhase)
        assertNull(state.vehicle.speedFailure)
        speed(10f); advance(3_000); stop()
        assertEquals("d1-1", playTicket().chapterId)
    }

    @Test fun disconnect_of_both_signals_resets_detection() = test {
        start(); driveAndArm()
        speed(0f); advance(2_000)
        controller.onVehicleEvent(VehicleEvent.SignalFailed(VehicleSignal.SPEED, SignalFailure.DISCONNECTED))
        controller.onVehicleEvent(VehicleEvent.SignalFailed(VehicleSignal.GEAR, SignalFailure.DISCONNECTED))
        advance(5_000)
        assertEquals(GatePhase.WAITING_DRIVE, state.gatePhase)
        speed(0f) // 기어를 아직 모름
        advance(5_000)
        assertEquals(GatePhase.WAITING_DRIVE, state.gatePhase)
        assertEquals(PlayDecision.Ignored(IgnoreReason.NOT_ARMED), play())
    }

    @Test fun gear_read_error_during_stop_confirmation_cancels_it() = test {
        start(); driveAndArm()
        speed(0f); advance(2_000)
        controller.onVehicleEvent(VehicleEvent.SignalFailed(VehicleSignal.GEAR, SignalFailure.READ_FAILED))
        advance(2_000)
        assertEquals(GatePhase.WAITING_DRIVE, state.gatePhase)
    }

    @Test fun data_error_variant_error_pauses_and_requires_redrive_after_recovery() = test {
        start(); driveAndArm(); stop()
        val t = playTicket()
        assertEquals(PlaybackPhase.PREPARING, state.playback)
        controller.onVehicleEvent(VehicleEvent.SignalFailed(VehicleSignal.SPEED, SignalFailure.ERROR))
        assertEquals(listOf(PlaybackEnd.VEHICLE_DATA_ERROR), player.pauses)
        assertEquals(PlaybackPhase.IDLE, state.playback)
        assertEquals(GatePhase.WAITING_DRIVE, state.gatePhase)
        assertNull(state.vehicle.speedKmh)
        assertEquals(SignalFailure.ERROR, state.vehicle.speedFailure)
        assertFalse(controller.isTicketValid(t))
        speed(0f); advance(10_000) // 복구됐지만 정차 값만: ARMED가 되지 않는다
        assertNull(state.vehicle.speedFailure)
        assertEquals(GatePhase.WAITING_DRIVE, state.gatePhase)
        assertEquals(PlayDecision.Ignored(IgnoreReason.NOT_ARMED), play())
        speed(10f); advance(3_000); stop()
        assertEquals("d1-1", playTicket().chapterId)
    }

    @Test fun speed_error_while_playing_pauses() = test {
        start(); driveAndArm(); stop()
        val t = playTicket()
        controller.onPlaybackStarted(t)
        assertEquals(PlaybackPhase.PLAYING, state.playback)
        controller.onVehicleEvent(VehicleEvent.SignalFailed(VehicleSignal.SPEED, SignalFailure.ERROR))
        assertEquals(listOf(PlaybackEnd.VEHICLE_DATA_ERROR), player.pauses)
        assertEquals(GatePhase.WAITING_DRIVE, state.gatePhase)
        assertEquals(SignalFailure.ERROR, state.vehicle.speedFailure)
    }

    @Test fun gear_error_while_playing_pauses_and_requires_redrive() = test {
        start(); driveAndArm(); stop()
        val t = playTicket()
        controller.onPlaybackStarted(t)
        controller.onVehicleEvent(VehicleEvent.SignalFailed(VehicleSignal.GEAR, SignalFailure.ERROR))
        assertEquals(listOf(PlaybackEnd.VEHICLE_DATA_ERROR), player.pauses)
        assertEquals(GatePhase.WAITING_DRIVE, state.gatePhase)
        assertNull(state.vehicle.gear)
        assertEquals(SignalFailure.ERROR, state.vehicle.gearFailure)
        gear(Gear.DRIVE); advance(10_000) // 복구, 속도는 0 그대로
        assertNull(state.vehicle.gearFailure)
        assertEquals(GatePhase.WAITING_DRIVE, state.gatePhase)
        assertEquals(PlayDecision.Ignored(IgnoreReason.NOT_ARMED), play())
    }

    // ---- 저장 순서 ----

    @Test fun persistence_runs_in_call_order_even_when_earlier_calls_finish_late() = test(
        FakeProgressRepository(Progress(mapOf("day1" to "d1-3"), "day1")).also {
            it.delays["restartContent"] = 10_000
            it.delays["saveActiveContent"] = 500
        },
    ) {
        start() // restartContent(10s)
        driveAndArm(); stop()
        val t = playTicket()
        complete(t) // 약 6초 시점. restartContent는 아직 안 끝났다
        assertTrue("nothing may finish before restartContent", repo.calls.isEmpty())
        advance(20_000)
        assertEquals(listOf("restartContent", "saveCompleted"), repo.calls)
        assertEquals(mapOf("day1" to "d1-1"), repo.progress.lastCompletedChapterIds)
    }

    @Test fun completed_saves_are_serialized_in_order() = test {
        repo.delays["saveCompleted"] = 5_000
        start(); driveAndArm(); stop()
        complete(playTicket())
        speed(10f); advance(3_000); stop()
        complete(playTicket())
        advance(30_000)
        assertEquals(listOf("day1" to "d1-1", "day1" to "d1-2"), repo.savedCompleted)
        assertEquals("d1-2", repo.progress.lastCompletedChapterIds["day1"])
    }

    // ---- 미지원·권한 거부 ----

    @Test fun permission_denied_disables_learning() = test {
        start(); driveAndArm(); stop()
        val t = playTicket()
        controller.onPlaybackStarted(t)
        controller.onVehicleEvent(VehicleEvent.SignalBlocked(VehicleSignal.SPEED, SignalBlock.PERMISSION_DENIED))
        assertFalse(state.learningOn)
        assertEquals(listOf(PlaybackEnd.LEARNING_STOPPED), player.pauses)
        assertEquals(mapOf(VehicleSignal.SPEED to SignalBlock.PERMISSION_DENIED), state.disabledReason?.blocked)
        assertTrue(controller.startLearning("day1") is StartResult.Disabled)
        assertEquals(PlayDecision.Ignored(IgnoreReason.DISABLED), play())
        assertFalse(state.learningOn)
        // 권한을 받아 값이 들어오면 다시 시작 가능
        speed(0f)
        assertNull(state.disabledReason)
        assertEquals(StartResult.Started, controller.startLearning("day1"))
    }

    @Test fun unsupported_gear_disables_learning() = test {
        controller.onVehicleEvent(VehicleEvent.SignalBlocked(VehicleSignal.GEAR, SignalBlock.UNSUPPORTED))
        assertTrue(controller.startLearning("day1") is StartResult.Disabled)
        assertEquals(PlayDecision.Ignored(IgnoreReason.DISABLED), play())
        assertFalse(state.learningOn)
    }

    // ---- 정차당 1회, 사용자 명령, 포커스 ----

    @Test fun pause_command_stops_and_no_retry_in_same_stop() = test {
        start(); driveAndArm(); stop()
        val t = playTicket()
        controller.onPlaybackStarted(t)
        controller.onPauseCommand("com.android.systemui")
        assertEquals(PlaybackPhase.IDLE, state.playback)
        assertEquals(PlaybackEnd.USER_PAUSE, state.lastPlaybackEnd)
        assertTrue(player.pauses.isEmpty()) // 정지는 MediaSession이 수행
        advance(60_000)
        assertEquals(PlayDecision.Ignored(IgnoreReason.STOP_ALREADY_USED), play())
        assertEquals("d1-1", state.currentChapterId)
    }

    @Test fun stop_command_keeps_learning_on_and_no_retry() = test {
        start(); driveAndArm(); stop()
        controller.onPlaybackStarted(playTicket())
        controller.onStopCommand("pkg")
        assertTrue(state.learningOn)
        assertEquals(PlaybackEnd.USER_STOP, state.lastPlaybackEnd)
        assertEquals(PlayDecision.Ignored(IgnoreReason.STOP_ALREADY_USED), play())
    }

    @Test fun pause_command_while_armed_does_not_consume_stop() = test {
        start(); driveAndArm(); stop()
        controller.onPauseCommand("pkg")
        assertEquals(GatePhase.ARMED, state.gatePhase)
        assertTrue(play() is PlayDecision.Play)
    }

    @Test fun focus_loss_pauses_without_retry_in_same_stop() = test {
        start(); driveAndArm(); stop()
        val t = playTicket()
        controller.onPlaybackStarted(t)
        controller.onPlaybackInterrupted(t, PlaybackEnd.FOCUS_LOSS)
        assertEquals(PlaybackEnd.FOCUS_LOSS, state.lastPlaybackEnd)
        advance(10 * 60_000L)
        assertEquals(PlayDecision.Ignored(IgnoreReason.STOP_ALREADY_USED), play())
        driveAndArm(); stop()
        assertEquals("d1-1", playTicket().chapterId)
    }

    @Test fun failure_before_start_still_consumes_stop() = test {
        start(); driveAndArm(); stop()
        val t = playTicket()
        controller.onPlaybackInterrupted(t, PlaybackEnd.ERROR) // 음원 준비 실패 등
        assertEquals(PlayDecision.Ignored(IgnoreReason.STOP_ALREADY_USED), play())
    }

    @Test fun play_while_preparing_or_playing_is_ignored() = test {
        start(); driveAndArm(); stop()
        val t = playTicket()
        assertEquals(PlayDecision.Ignored(IgnoreReason.ALREADY_PLAYING), play())
        controller.onPlaybackStarted(t)
        assertEquals(PlayDecision.Ignored(IgnoreReason.ALREADY_PLAYING), play())
        assertTrue(controller.isTicketValid(t))
    }

    @Test fun one_play_per_stop_across_many_stops() = test {
        start()
        var plays = 0
        repeat(2) {
            driveAndArm(); stop()
            repeat(3) { if (play() is PlayDecision.Play) plays++ }
            val last = state
            assertEquals(GatePhase.USED, last.gatePhase)
            controller.onPauseCommand("pkg")
        }
        assertEquals(2, plays)
    }

    @Test fun logs_media_command_source() = test {
        play()
        assertTrue(logs.any { it.contains("media PLAY from=com.test.controller") })
    }

    // W3: Play 허가 뒤 준비 중 서행(0.5, 1] → 시작하지 않음(새 재생 금지), 정차 시도는 소모
    @Test fun creep_while_preparing_cancels_start() = test {
        start(); driveAndArm(); stop()
        val t = playTicket()
        assertEquals(PlaybackPhase.PREPARING, state.playback)
        speed(0.5f) // 경계: 0.5는 정차 범위 → 계속 준비
        assertTrue(controller.isTicketValid(t))
        speed(0.8f)
        assertEquals(listOf(PlaybackEnd.MOVING), player.pauses)
        assertEquals(PlaybackPhase.IDLE, state.playback)
        assertFalse(controller.isTicketValid(t))
        speed(0f); advance(5_000)
        assertEquals(PlayDecision.Ignored(IgnoreReason.STOP_ALREADY_USED), play())
    }

    @Test fun creep_while_playing_does_not_stop() = test {
        start(); driveAndArm(); stop()
        val t = playTicket()
        controller.onPlaybackStarted(t)
        speed(0.8f)
        assertTrue(player.pauses.isEmpty())
        assertEquals(PlaybackPhase.PLAYING, state.playback)
    }

    // W9: 저장 실패는 크래시 없이 로그로 남고, 완료 로그는 저장이 끝난 뒤에만 찍힌다
    @Test fun save_failure_is_logged_not_thrown() = test {
        repo.failSaveCompleted = true
        start(); driveAndArm(); stop()
        complete(playTicket())
        advance(1_000)
        assertTrue(logs.any { it.startsWith("progress save FAILED") })
        assertFalse(logs.any { it.contains("d1-1 completed") && it.startsWith("progress saved") })
        assertEquals("d1-2", state.currentChapterId) // 메모리 진도는 유지
    }

    // ---- 정차 자동 재생(사용자 결정 2026-11-14) ----

    private fun Harness.stopForAutoPlay() {
        speed(0f)
        advance(3_000)
    }

    @Test fun auto_play_starts_on_confirmed_stop() = test {
        player.autoCapable = true
        start(); driveAndArm(); stopForAutoPlay()
        assertEquals(1, player.autoStarts.size)
        assertEquals("d1-1", player.autoStarts.single().chapterId)
        assertEquals(GatePhase.USED, state.gatePhase)
        assertEquals(PlaybackPhase.PREPARING, state.playback)
        assertTrue(controller.isTicketValid(player.autoStarts.single()))
    }

    @Test fun auto_play_on_first_stop_right_after_start() = test {
        player.autoCapable = true
        gear(Gear.DRIVE); speed(0f)
        start()
        assertTrue(player.autoStarts.isEmpty())
        advance(3_000)
        assertEquals(1, player.autoStarts.size)
    }

    @Test fun auto_play_waits_for_player_without_using_the_stop() = test {
        start(); driveAndArm(); stop()
        assertTrue(player.autoStarts.isEmpty())
        assertEquals(GatePhase.ARMED, state.gatePhase)
        player.autoCapable = true
        controller.tryAutoPlay()
        assertEquals(1, player.autoStarts.size)
        assertEquals(GatePhase.USED, state.gatePhase)
    }

    @Test fun moving_stops_auto_played_track() = test {
        player.autoCapable = true
        start(); driveAndArm(); stopForAutoPlay()
        val t = player.autoStarts.single()
        controller.onPlaybackStarted(t)
        speed(5f)
        assertEquals(listOf<PauseReason>(PlaybackEnd.MOVING), player.pauses)
        assertEquals(PlaybackPhase.IDLE, state.playback)
        assertFalse(controller.isTicketValid(t))
    }

    @Test fun auto_play_once_per_stop() = test {
        player.autoCapable = true
        start(); driveAndArm(); stopForAutoPlay()
        val t = player.autoStarts.single()
        controller.onPlaybackInterrupted(t, PlaybackEnd.FOCUS_LOSS)
        controller.tryAutoPlay()
        speed(0.3f); speed(0f)
        assertEquals(1, player.autoStarts.size)
        // 재주행 뒤 다음 정차에서 다음 턴을 다시 자동 재생(중단된 d1-1부터)
        driveAndArm(); stopForAutoPlay()
        assertEquals(listOf("d1-1", "d1-1"), player.autoStarts.map { it.chapterId })
    }

    @Test fun no_auto_play_while_learning_off() = test {
        player.autoCapable = true
        driveAndArmIgnored()
        controller.tryAutoPlay()
        assertTrue(player.autoStarts.isEmpty())
    }

    @Test fun no_auto_play_while_creeping_until_stopped_again() = test {
        start(); driveAndArm(); stop()
        speed(0.8f)
        assertEquals(GatePhase.ARMED_CREEP, state.gatePhase)
        player.autoCapable = true
        controller.tryAutoPlay()
        assertTrue(player.autoStarts.isEmpty())
        speed(0f)
        assertEquals(1, player.autoStarts.size)
    }

    @Test fun progress_saved_logged_after_save_completes() = test {
        repo.delays["saveCompleted"] = 2_000
        start(); driveAndArm(); stop()
        complete(playTicket())
        assertFalse(logs.any { it == "progress saved: day1/d1-1 completed" })
        advance(2_500)
        assertTrue(logs.any { it == "progress saved: day1/d1-1 completed" })
    }
}
