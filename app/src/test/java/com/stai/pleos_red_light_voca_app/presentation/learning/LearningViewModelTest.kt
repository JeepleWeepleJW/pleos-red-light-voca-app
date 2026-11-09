package com.stai.pleos_red_light_voca_app.presentation.learning

import com.stai.pleos_red_light_voca_app.core.common.LearningConfig
import com.stai.pleos_red_light_voca_app.core.common.Logger
import com.stai.pleos_red_light_voca_app.core.common.MonotonicClock
import com.stai.pleos_red_light_voca_app.core.entity.Catalog
import com.stai.pleos_red_light_voca_app.core.entity.Chapter
import com.stai.pleos_red_light_voca_app.core.entity.Content
import com.stai.pleos_red_light_voca_app.core.entity.Progress
import com.stai.pleos_red_light_voca_app.core.entity.VehicleEvent
import com.stai.pleos_red_light_voca_app.domain.repository.CatalogRepository
import com.stai.pleos_red_light_voca_app.domain.repository.CatalogResult
import com.stai.pleos_red_light_voca_app.domain.repository.ProgressRepository
import com.stai.pleos_red_light_voca_app.domain.session.AppSession
import com.stai.pleos_red_light_voca_app.domain.session.AudioStatus
import com.stai.pleos_red_light_voca_app.domain.speech.ChapterAudioResult
import com.stai.pleos_red_light_voca_app.domain.source.AudioAvailability
import com.stai.pleos_red_light_voca_app.domain.source.ChapterAudioSource
import com.stai.pleos_red_light_voca_app.domain.source.VehicleStateSource
import com.stai.pleos_red_light_voca_app.domain.source.VehicleStateSourceFactory
import com.stai.pleos_red_light_voca_app.domain.usecase.ObserveLearningStateUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.ObserveSessionStateUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.ObserveAudioStatusUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.RestartVehicleUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.SelectContentUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.StartAppUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.StartLearningUseCase
import com.stai.pleos_red_light_voca_app.domain.usecase.StopLearningUseCase
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.flow.MutableSharedFlow
import com.stai.pleos_red_light_voca_app.core.entity.Gear
import com.stai.pleos_red_light_voca_app.domain.gate.GatePhase
import com.stai.pleos_red_light_voca_app.domain.learning.PlaybackPhase
import com.stai.pleos_red_light_voca_app.domain.playback.PlayCommandResult
import com.stai.pleos_red_light_voca_app.domain.playback.PlaySource
import com.stai.pleos_red_light_voca_app.domain.usecase.HandlePlayCommandUseCase
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private val catalog = Catalog(
    listOf(
        Content("day1", "Day 1", (1..2).map { Chapter("d1-$it", "w$it", "noun", "뜻$it") }),
        Content("day2", "Day 2", (1..2).map { Chapter("d2-$it", "x$it", "verb", "뜻$it") }),
    ),
)

/** 결과를 테스트가 정할 때까지 기다리는 카탈로그 저장소 */
private class FakeCatalogRepository : CatalogRepository {
    val result = CompletableDeferred<CatalogResult>()
    override suspend fun load(): CatalogResult = result.await()
}

private class FakeProgressRepository : ProgressRepository {
    override suspend fun load(): Progress = Progress()
    override suspend fun saveCompleted(contentId: String, chapterId: String) = Unit
    override suspend fun clear(contentId: String) = Unit
    override suspend fun restartContent(contentId: String) = Unit
    override suspend fun saveActiveContent(contentId: String) = Unit
}

private val ALL_AUDIO = AudioAvailability.Checked(emptyMap())
private val DAY1_MISSING = AudioAvailability.Checked(mapOf("day1" to listOf("audio/day1/d1-1.en.wav")))

private class FakeAudioSource(var availability: AudioAvailability) : ChapterAudioSource {
    var checks = 0
    override suspend fun checkAvailability(contents: List<Content>): AudioAvailability {
        checks++
        return availability
    }
    override suspend fun chapterAudio(contentId: String, chapter: Chapter): ChapterAudioResult =
        ChapterAudioResult.Ready(File("unused.wav"))
    override suspend fun prefetch(contentId: String, chapters: List<Chapter>) = Unit
}

/** 차량 수집을 (다시) 시작한 횟수를 센다. 테스트가 [events]로 넣은 차량 이벤트만 내보낸다. */
private class CountingVehicleSourceFactory : VehicleStateSourceFactory {
    var created = 0
    val events = MutableSharedFlow<VehicleEvent>(extraBufferCapacity = 16)
    override fun create(): VehicleStateSource {
        created++
        return object : VehicleStateSource {
            override fun events(): Flow<VehicleEvent> = this@CountingVehicleSourceFactory.events
        }
    }
}

/**
 * 실제 [AppSession]과 실제 UseCase를 di(`AppModule`·`UseCaseModule`)와 같은 방식으로 조립하고,
 * 저장소·음원 소스·차량 소스만 가짜로 넣는다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private class Harness(
    scope: CoroutineScope,
    audio: AudioAvailability = ALL_AUDIO,
    now: () -> Long = { 0L },
) {
    val catalogRepo = FakeCatalogRepository()
    val audioSource = FakeAudioSource(audio)
    val vehicleFactory = CountingVehicleSourceFactory()
    val session = AppSession(
        catalogRepository = catalogRepo,
        progressRepository = FakeProgressRepository(),
        audioSource = audioSource,
        vehicleSourceFactory = vehicleFactory,
        clock = MonotonicClock { now() },
        scope = scope,
        config = LearningConfig(),
        logger = Logger.NONE,
    )

    fun startApp() = StartAppUseCase(session)()

    fun viewModel() = LearningViewModel(
        observeSessionState = ObserveSessionStateUseCase(session),
        observeLearningState = ObserveLearningStateUseCase(session),
        observeAudioStatus = ObserveAudioStatusUseCase(session),
        restartVehicle = RestartVehicleUseCase(session),
        startLearning = StartLearningUseCase(session),
        stopLearning = StopLearningUseCase(session),
        selectContent = SelectContentUseCase(session),
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
class LearningViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun perms(speed: Boolean, notifApplicable: Boolean = false, notif: Boolean = false) =
        PermissionUiModel(speedGranted = speed, notificationApplicable = notifApplicable, notificationGranted = notif)

    /** 세션을 Ready까지 올리고 ViewModel을 만든다. 이 시점 차량 소스 생성은 1회(앱 시작 시). */
    private fun TestScope.readyHarness(audio: AudioAvailability = ALL_AUDIO): Pair<Harness, LearningViewModel> {
        val h = Harness(backgroundScope, audio) { testScheduler.currentTime }
        h.startApp()
        val vm = h.viewModel()
        h.catalogRepo.result.complete(CatalogResult.Success(catalog))
        runCurrent()
        assertEquals("앱 시작 시 차량 수집 1회", 1, h.vehicleFactory.created)
        return h to vm
    }

    @Test fun return_to_screen_restarts_vehicle_only_when_car_speed_changed() = runTest(dispatcher) {
        val (h, vm) = readyHarness()

        vm.onAction(LearningAction.OpenScreen(perms(speed = true)))
        runCurrent()
        assertEquals("화면을 열 때는 다시 시작하지 않는다", 1, h.vehicleFactory.created)

        // 알림 권한만 바뀌어도 CAR_SPEED가 같으면 다시 시작하지 않는다
        vm.onAction(LearningAction.ReturnToScreen(perms(speed = true, notifApplicable = true, notif = true)))
        runCurrent()
        assertEquals("같은 CAR_SPEED 값으로 돌아오면 재시작 없음", 1, h.vehicleFactory.created)

        vm.onAction(LearningAction.ReturnToScreen(perms(speed = false)))
        runCurrent()
        assertEquals("CAR_SPEED가 바뀌면 재시작 1회", 2, h.vehicleFactory.created)

        vm.onAction(LearningAction.ReturnToScreen(perms(speed = false)))
        runCurrent()
        assertEquals("직전에 본 값과 비교한다(다시 같으면 재시작 없음)", 2, h.vehicleFactory.created)

        vm.onAction(LearningAction.ReturnToScreen(perms(speed = true)))
        runCurrent()
        assertEquals(3, h.vehicleFactory.created)
    }

    @Test fun answer_permission_request_always_restarts_vehicle() = runTest(dispatcher) {
        val (h, vm) = readyHarness()
        vm.onAction(LearningAction.OpenScreen(perms(speed = true)))

        vm.onAction(LearningAction.AnswerPermissionRequest(perms(speed = true)))
        runCurrent()
        assertEquals("값이 같아도 재시작", 2, h.vehicleFactory.created)

        vm.onAction(LearningAction.AnswerPermissionRequest(perms(speed = false)))
        runCurrent()
        assertEquals("값이 바뀌어도 재시작", 3, h.vehicleFactory.created)
    }

    @Test fun click_start_with_missing_audio_shows_refusal_and_click_stop_clears_it() = runTest(dispatcher) {
        val (h, vm) = readyHarness(audio = DAY1_MISSING)
        runCurrent()
        assertEquals(AudioStatus.Checked(DAY1_MISSING.missing), vm.uiState.value.audio)
        assertEquals("day1", vm.uiState.value.learning.contentId)

        vm.onAction(LearningAction.ClickStart)
        runCurrent()
        assertEquals("음원이 준비되지 않아 학습을 시작할 수 없습니다: Day 1 (문제 파일 1개)", vm.uiState.value.startRefusedMessage)
        assertFalse(vm.uiState.value.learning.learningOn)
        assertTrue("시작 전에 음원을 다시 확인한다", h.audioSource.checks >= 2)

        vm.onAction(LearningAction.ClickStop)
        runCurrent()
        assertNull(vm.uiState.value.startRefusedMessage)
    }

    @Test fun select_content_clears_start_refused_message() = runTest(dispatcher) {
        val (_, vm) = readyHarness(audio = DAY1_MISSING)
        runCurrent()
        vm.onAction(LearningAction.ClickStart)
        runCurrent()
        assertEquals("음원이 준비되지 않아 학습을 시작할 수 없습니다: Day 1 (문제 파일 1개)", vm.uiState.value.startRefusedMessage)

        vm.onAction(LearningAction.SelectContent("day2"))
        runCurrent()
        assertNull(vm.uiState.value.startRefusedMessage)
        assertEquals("day2", vm.uiState.value.learning.contentId)
    }

    @Test fun click_start_with_audio_error_shows_refusal() = runTest(dispatcher) {
        val (_, vm) = readyHarness(audio = AudioAvailability.Error("io"))
        runCurrent()
        assertEquals(AudioStatus.Unavailable("음원 확인 오류: io"), vm.uiState.value.audio)
        vm.onAction(LearningAction.ClickStart)
        runCurrent()
        assertEquals("음원 확인 오류: io", vm.uiState.value.startRefusedMessage)
        assertFalse(vm.uiState.value.learning.learningOn)
    }

    @Test fun click_listen_sends_nothing_when_button_is_disabled() = runTest(dispatcher) {
        val (_, vm) = readyHarness()
        // 학습 OFF
        vm.onAction(LearningAction.ClickListen)
        assertNull(withTimeoutOrNull(1_000) { vm.events.first() })
        // 학습 ON, 아직 정차 확정 전(차량 입력 없음)
        vm.onAction(LearningAction.ClickStart)
        runCurrent()
        assertTrue(vm.uiState.value.learning.learningOn)
        assertFalse(ListenButton.of(vm.uiState.value.learning, vm.uiState.value.audio).enabled)
        vm.onAction(LearningAction.ClickListen)
        assertNull(withTimeoutOrNull(1_000) { vm.events.first() })
    }

    @Test fun click_listen_sends_play_when_armed_and_pause_while_preparing() = runTest(dispatcher) {
        val (h, vm) = readyHarness()
        vm.onAction(LearningAction.ClickStart)
        runCurrent()
        fun emit(e: VehicleEvent) { assertTrue(h.vehicleFactory.events.tryEmit(e)); runCurrent() }
        emit(VehicleEvent.GearChanged(Gear.DRIVE))
        emit(VehicleEvent.SpeedChanged(30f))
        advanceTimeBy(3_000); runCurrent()
        emit(VehicleEvent.SpeedChanged(0f))
        advanceTimeBy(3_000); runCurrent()
        assertEquals(GatePhase.ARMED, vm.uiState.value.learning.gatePhase)

        vm.onAction(LearningAction.ClickListen)
        assertEquals(LearningEvent.SendListenCommand(ListenCommand.PLAY), vm.events.first())

        // 세션이 그 명령을 받아 재생을 허가한 상태(준비 중)를 만든다: 버튼은 멈춤
        assertTrue(HandlePlayCommandUseCase(h.session, Logger.NONE)(PlaySource.APP_UI, "test") is PlayCommandResult.Play)
        runCurrent()
        assertEquals(PlaybackPhase.PREPARING, vm.uiState.value.learning.playback)
        vm.onAction(LearningAction.ClickListen)
        assertEquals(LearningEvent.SendListenCommand(ListenCommand.PAUSE), vm.events.first())
    }

    @Test fun open_screen_requests_only_missing_permissions_once() = runTest(dispatcher) {
        val (h, vm) = readyHarness()

        // 처음 화면을 열면 허용되지 않은 권한만 요청한다(알림은 API 33 이상일 때만)
        vm.onAction(LearningAction.OpenScreen(perms(speed = false, notifApplicable = true, notif = false)))
        assertEquals(
            LearningEvent.RequestPermissions(listOf(PERM_CAR_SPEED, PERM_POST_NOTIFICATIONS)),
            vm.events.first(),
        )
        runCurrent()
        assertEquals("요청만으로는 차량 수집을 다시 시작하지 않는다", 1, h.vehicleFactory.created)

        // 구성 변경(회전 등)으로 화면이 다시 열리거나 ON_RESUME으로 돌아와도 다시 요청하지 않는다
        vm.onAction(LearningAction.OpenScreen(perms(speed = false, notifApplicable = true, notif = false)))
        vm.onAction(LearningAction.ReturnToScreen(perms(speed = false, notifApplicable = true, notif = false)))
        assertNull(withTimeoutOrNull(1_000) { vm.events.first() })

        // 요청 창 답은 기존대로 차량 수집을 다시 시작한다
        vm.onAction(LearningAction.AnswerPermissionRequest(perms(speed = true, notifApplicable = true, notif = true)))
        runCurrent()
        assertEquals(2, h.vehicleFactory.created)
    }

    @Test fun open_screen_requests_only_car_speed_below_api_33_or_when_notification_granted() = runTest(dispatcher) {
        val (_, vm) = readyHarness()
        vm.onAction(LearningAction.OpenScreen(perms(speed = false, notifApplicable = false)))
        assertEquals(LearningEvent.RequestPermissions(listOf(PERM_CAR_SPEED)), vm.events.first())

        val (_, vm2) = readyHarness()
        vm2.onAction(LearningAction.OpenScreen(perms(speed = true, notifApplicable = true, notif = false)))
        assertEquals(LearningEvent.RequestPermissions(listOf(PERM_POST_NOTIFICATIONS)), vm2.events.first())
    }

    @Test fun open_screen_with_all_permissions_granted_requests_nothing() = runTest(dispatcher) {
        val (_, vm) = readyHarness()
        vm.onAction(LearningAction.OpenScreen(perms(speed = true, notifApplicable = true, notif = true)))
        assertNull(withTimeoutOrNull(1_000) { vm.events.first() })
        // 그 뒤 권한이 꺼져도(앱 설정) 자동으로 다시 요청하지 않는다
        vm.onAction(LearningAction.ReturnToScreen(perms(speed = false, notifApplicable = true, notif = true)))
        vm.onAction(LearningAction.OpenScreen(perms(speed = false, notifApplicable = true, notif = true)))
        assertNull(withTimeoutOrNull(1_000) { vm.events.first() })
    }

    @Test fun ui_state_follows_session_loading_and_ready() = runTest(dispatcher) {
        val h = Harness(backgroundScope)
        h.startApp()
        val vm = h.viewModel()
        runCurrent()
        vm.uiState.value.let {
            assertTrue("로딩 중", it.isLoading)
            assertNull(it.loadErrorMessage)
            assertNull("로딩 중에는 카탈로그를 그리지 않는다", it.catalog)
        }

        h.catalogRepo.result.complete(CatalogResult.Success(catalog))
        runCurrent()
        vm.uiState.value.let {
            assertFalse(it.isLoading)
            assertNull(it.loadErrorMessage)
            assertEquals(catalog, it.catalog)
            assertEquals("Ready 뒤 학습 상태가 들어온다", "day1", it.learning.contentId)
            assertEquals(2, it.learning.totalChapters)
        }

        vm.onAction(LearningAction.SelectContent("day2"))
        runCurrent()
        assertEquals("day2", vm.uiState.value.learning.contentId)
    }

    @Test fun ui_state_shows_load_failure() = runTest(dispatcher) {
        val h = Harness(backgroundScope)
        h.startApp()
        val vm = h.viewModel()
        runCurrent()
        assertTrue(vm.uiState.value.isLoading)

        h.catalogRepo.result.complete(CatalogResult.Failure("catalog.json 없음"))
        runCurrent()
        vm.uiState.value.let {
            assertFalse(it.isLoading)
            assertEquals("catalog.json 없음", it.loadErrorMessage)
            assertNull(it.catalog)
            assertNull("실패면 학습 상태가 들어오지 않는다", it.learning.contentId)
        }
        assertEquals("실패면 차량 수집을 시작하지 않는다", 0, h.vehicleFactory.created)

        // 앱 준비 전 동작은 무시된다(재시작·선택 없음)
        vm.onAction(LearningAction.AnswerPermissionRequest(perms(speed = true)))
        vm.onAction(LearningAction.SelectContent("day2"))
        runCurrent()
        assertEquals(0, h.vehicleFactory.created)
        assertNull(vm.uiState.value.learning.contentId)
    }
}
