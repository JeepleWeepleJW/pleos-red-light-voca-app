package com.stai.pleos_red_light_voca_app.domain.usecase

import com.stai.pleos_red_light_voca_app.core.common.LearningConfig
import com.stai.pleos_red_light_voca_app.core.common.Logger
import com.stai.pleos_red_light_voca_app.core.common.MonotonicClock
import com.stai.pleos_red_light_voca_app.core.entity.Catalog
import com.stai.pleos_red_light_voca_app.core.entity.Chapter
import com.stai.pleos_red_light_voca_app.core.entity.Content
import com.stai.pleos_red_light_voca_app.core.entity.Gear
import com.stai.pleos_red_light_voca_app.core.entity.Progress
import com.stai.pleos_red_light_voca_app.core.entity.VehicleEvent
import com.stai.pleos_red_light_voca_app.domain.gate.GatePhase
import com.stai.pleos_red_light_voca_app.domain.learning.LearningState
import com.stai.pleos_red_light_voca_app.domain.learning.PlayTicket
import com.stai.pleos_red_light_voca_app.domain.learning.PlaybackPhase
import com.stai.pleos_red_light_voca_app.domain.playback.PlayCommandResult
import com.stai.pleos_red_light_voca_app.domain.playback.PlaySource
import com.stai.pleos_red_light_voca_app.domain.repository.CatalogRepository
import com.stai.pleos_red_light_voca_app.domain.repository.CatalogResult
import com.stai.pleos_red_light_voca_app.domain.repository.ProgressRepository
import com.stai.pleos_red_light_voca_app.domain.session.AppSession
import com.stai.pleos_red_light_voca_app.domain.session.SessionState
import com.stai.pleos_red_light_voca_app.domain.session.AudioStatus
import com.stai.pleos_red_light_voca_app.domain.source.AudioAvailability
import com.stai.pleos_red_light_voca_app.domain.source.ChapterAudioSource
import com.stai.pleos_red_light_voca_app.domain.source.VehicleStateSource
import com.stai.pleos_red_light_voca_app.domain.source.VehicleStateSourceFactory
import com.stai.pleos_red_light_voca_app.domain.speech.ChapterAudioResult
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val catalog = Catalog(
    listOf(Content("day1", "Day 1", (1..2).map { Chapter("d1-$it", "w$it", "noun", "뜻$it") })),
)

/** 결과를 테스트가 정할 때까지 기다리는 카탈로그 저장소(콜드 스타트 재현) */
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

/** 미리 병합 호출을 (콘텐츠, 챕터 id 목록)으로 기록한다. */
private class FakeAudioSource(private val availability: AudioAvailability) : ChapterAudioSource {
    val prefetches = mutableListOf<Pair<String, List<String>>>()
    override suspend fun checkAvailability(contents: List<Content>): AudioAvailability = availability
    override suspend fun chapterAudio(contentId: String, chapter: Chapter): ChapterAudioResult =
        ChapterAudioResult.Ready(File("unused.wav"))
    override suspend fun prefetch(contentId: String, chapters: List<Chapter>) {
        prefetches += contentId to chapters.map { it.chapterId }
    }
}

/** 테스트가 차량 이벤트를 넣는 소스. 수집을 다시 시작해도 같은 흐름을 본다. */
private class FakeVehicleSourceFactory : VehicleStateSourceFactory {
    val events = MutableSharedFlow<VehicleEvent>(extraBufferCapacity = 16)
    override fun create(): VehicleStateSource = object : VehicleStateSource {
        override fun events(): Flow<VehicleEvent> = this@FakeVehicleSourceFactory.events
    }
}

/** 받은 로그(i·w)를 순서대로 남긴다. */
private class RecordingLogger : Logger {
    val logs = mutableListOf<String>()
    override fun i(message: String) {
        logs += message
    }
    override fun w(message: String, t: Throwable?) {
        logs += message
    }
}

/**
 * 실제 [AppSession]·LearningController·StopGate와 실제 UseCase를 di와 같은 방식으로 조립하고,
 * 저장소·음원 소스·차량 소스만 가짜로 넣는다(조립은 `LearningViewModelTest`와 같다). 시계는 테스트 스케줄러 시간.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private class Harness(val scope: TestScope, audio: AudioAvailability = ALL_AUDIO) {
    val catalogRepo = FakeCatalogRepository()
    val audioSource = FakeAudioSource(audio)
    val vehicle = FakeVehicleSourceFactory()
    val logger = RecordingLogger()
    val session = AppSession(
        catalogRepository = catalogRepo,
        progressRepository = FakeProgressRepository(),
        audioSource = audioSource,
        vehicleSourceFactory = vehicle,
        clock = MonotonicClock { scope.testScheduler.currentTime },
        scope = scope.backgroundScope,
        config = LearningConfig(),
        logger = logger,
    )
    val handlePlay = HandlePlayCommandUseCase(session, logger)
    private val getLearningState = GetLearningStateUseCase(session)

    val learning: LearningState get() = getLearningState()!!

    /** 앱 시작만(카탈로그 로딩 중에 멈춰 있다) */
    fun startApp() {
        StartAppUseCase(session)()
        scope.runCurrent()
    }

    /** 카탈로그 로딩·음원 확인까지 끝낸다. */
    fun ready() {
        startApp()
        catalogRepo.result.complete(CatalogResult.Success(catalog))
        scope.runCurrent()
        assertTrue(session.state.value is SessionState.Ready)
        assertTrue(session.audio.value !is AudioStatus.Checking)
    }

    suspend fun play(source: PlaySource): PlayCommandResult =
        handlePlay(source, "com.test.controller").also { scope.runCurrent() }

    fun emit(e: VehicleEvent) {
        assertTrue(vehicle.events.tryEmit(e))
        scope.runCurrent()
    }

    fun advance(ms: Long) {
        scope.advanceTimeBy(ms)
        scope.runCurrent()
    }

    fun logged(fragment: String) = logger.logs.any { fragment in it }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HandlePlayCommandUseCaseTest {

    private fun test(audio: AudioAvailability = ALL_AUDIO, body: suspend Harness.() -> Unit) = runTest {
        Harness(this, audio).body()
    }

    @Test fun learning_off_other_controller_is_ignored_and_stays_off() = test {
        ready()
        assertEquals(PlayCommandResult.Ignored, play(PlaySource.OTHER_CONTROLLER))
        assertFalse(learning.learningOn)
        assertTrue(logger.logs.toString(), logged("ignored: learning OFF, only a media key starts learning"))
        assertTrue(audioSource.prefetches.isEmpty())
    }

    @Test fun system_autoplay_is_always_ignored() = test {
        ready()
        assertEquals(PlayCommandResult.Ignored, play(PlaySource.SYSTEM_AUTOPLAY))
        assertFalse(learning.learningOn)
        assertTrue(logger.logs.toString(), logged("ignored: system auto-play"))
        // 학습 ON에서도 무시(학습 상태도 재생도 바꾸지 않는다)
        assertEquals(PlayCommandResult.Forwarded, play(PlaySource.MEDIA_KEY))
        assertTrue(learning.learningOn)
        assertEquals(PlayCommandResult.Ignored, play(PlaySource.SYSTEM_AUTOPLAY))
        assertTrue(learning.learningOn)
        assertEquals(PlaybackPhase.IDLE, learning.playback)
    }

    @Test fun learning_off_media_key_with_missing_audio_is_ignored() =
        test(AudioAvailability.Checked(mapOf("day1" to listOf("audio/day1/d1-2.ko.wav")))) {
            ready()
            assertFalse(session.audio.value.isReadyFor("day1"))
            assertEquals(PlayCommandResult.Ignored, play(PlaySource.MEDIA_KEY))
            assertFalse(learning.learningOn)
            assertTrue(logger.logs.toString(), logged("ignored: learning OFF and audio not ready (음원 준비 안 됨: day1 (문제 파일 1개, 예: audio/day1/d1-2.ko.wav))"))
            assertTrue(audioSource.prefetches.isEmpty())
        }

    @Test fun learning_off_media_key_with_audio_error_is_ignored() = test(AudioAvailability.Error("io")) {
        ready()
        assertTrue(session.audio.value is AudioStatus.Unavailable)
        assertTrue(logger.logs.toString(), logged("audio status: Unavailable(음원 확인 오류: io)"))
        assertEquals(PlayCommandResult.Ignored, play(PlaySource.MEDIA_KEY))
        assertFalse(learning.learningOn)
        assertTrue(logger.logs.toString(), logged("ignored: learning OFF and audio not ready (음원 확인 오류: io)"))
        assertTrue(audioSource.prefetches.isEmpty())
    }

    @Test fun audio_status_log_lines() {
        test { ready(); assertTrue(logger.logs.toString(), logged("audio status: Ready (all contents)")) }
        test(AudioAvailability.Checked(mapOf("day1" to listOf("audio/day1/d1-2.ko.wav", "audio/day1/d1-1.en.wav (지문 없음)")))) {
            ready()
            assertTrue(logger.logs.toString(), logged("audio status: Missing day1=2 files (e.g. audio/day1/d1-2.ko.wav)"))
        }
    }

    @Test fun missing_audio_of_other_content_does_not_block() =
        test(AudioAvailability.Checked(mapOf("other" to listOf("audio/other/x.en.wav")))) {
            ready()
            assertEquals(PlayCommandResult.Forwarded, play(PlaySource.MEDIA_KEY))
            assertTrue(learning.learningOn)
        }

    @Test fun learning_off_media_key_with_audio_turns_learning_on_and_prefetches() = test {
        ready()
        assertEquals(PlayCommandResult.Forwarded, play(PlaySource.MEDIA_KEY))
        assertTrue(learning.learningOn)
        assertEquals(PlaybackPhase.IDLE, learning.playback)
        assertEquals(listOf("day1" to listOf("d1-1", "d1-2")), audioSource.prefetches)
        assertTrue(logger.logs.toString(), logged("source=MEDIA_KEY -> LearningController"))
    }

    @Test fun learning_on_but_not_armed_is_forwarded_without_playback() = test {
        ready()
        assertEquals(PlayCommandResult.Forwarded, play(PlaySource.MEDIA_KEY))
        assertNotEquals(GatePhase.ARMED, learning.gatePhase)
        // 학습 ON이면 미디어 키 밖의 사용자 명령도 컨트롤러로 넘긴다. 정차 준비가 안 됐으니 재생은 없다.
        assertEquals(PlayCommandResult.Forwarded, play(PlaySource.OTHER_CONTROLLER))
        assertEquals(PlayCommandResult.Forwarded, play(PlaySource.MEDIA_KEY))
        assertTrue(learning.learningOn)
        assertEquals(PlaybackPhase.IDLE, learning.playback)
    }

    @Test fun armed_stop_gives_play_ticket() = test {
        ready()
        assertEquals(PlayCommandResult.Forwarded, play(PlaySource.MEDIA_KEY))
        // D단 30 km/h 3초 → 감지, 0 km/h 3초 → ARMED (차량 소스로 이벤트 주입)
        emit(VehicleEvent.GearChanged(Gear.DRIVE))
        emit(VehicleEvent.SpeedChanged(30f))
        advance(3_000)
        assertEquals(GatePhase.DETECTING, learning.gatePhase)
        emit(VehicleEvent.SpeedChanged(0f))
        advance(3_000)
        assertEquals(GatePhase.ARMED, learning.gatePhase)
        assertEquals(PlayCommandResult.Play(PlayTicket(1, "day1", "d1-1")), play(PlaySource.OTHER_CONTROLLER))
        assertEquals(PlaybackPhase.PREPARING, learning.playback)
        // 같은 정차에서 다시 Play: 재생 없이 넘긴다
        assertEquals(PlayCommandResult.Forwarded, play(PlaySource.MEDIA_KEY))
    }

    @Test fun app_ui_button_does_not_turn_learning_on() = test {
        ready()
        assertEquals(PlayCommandResult.Ignored, play(PlaySource.APP_UI))
        assertFalse(learning.learningOn)
        assertTrue(logger.logs.toString(), logged("source=APP_UI ignored: learning OFF, only a media key starts learning"))
        assertTrue(audioSource.prefetches.isEmpty())
    }

    @Test fun app_ui_button_follows_same_gate_as_media_key() = test {
        ready()
        assertEquals(PlayCommandResult.Forwarded, play(PlaySource.MEDIA_KEY))
        // 학습 ON이지만 정차 확정 전: 재생 없음
        assertEquals(PlayCommandResult.Forwarded, play(PlaySource.APP_UI))
        assertEquals(PlaybackPhase.IDLE, learning.playback)
        emit(VehicleEvent.GearChanged(Gear.DRIVE))
        emit(VehicleEvent.SpeedChanged(30f))
        advance(3_000)
        emit(VehicleEvent.SpeedChanged(0f))
        advance(3_000)
        assertEquals(GatePhase.ARMED, learning.gatePhase)
        assertEquals(PlayCommandResult.Play(PlayTicket(1, "day1", "d1-1")), play(PlaySource.APP_UI))
        assertTrue(logger.logs.toString(), logged("source=APP_UI -> LearningController"))
        // 같은 정차에서 다시 누름: 재생 없음(정차당 1회)
        assertEquals(PlayCommandResult.Forwarded, play(PlaySource.APP_UI))
    }

    @Test fun cold_start_media_key_waits_and_is_handled_with_same_source() = test {
        startApp()
        assertTrue(session.state.value is SessionState.Loading)
        val pending = scope.async { handlePlay(PlaySource.MEDIA_KEY, "com.test.controller") }
        scope.runCurrent()
        assertFalse(pending.isCompleted)
        assertTrue(logger.logs.toString(), logged("source=MEDIA_KEY deferred until app/audio ready"))
        catalogRepo.result.complete(CatalogResult.Success(catalog))
        scope.runCurrent()
        // 준비 뒤 같은 출처(MEDIA_KEY)로 다시 처리: 학습 OFF → 학습 ON
        assertEquals(PlayCommandResult.Forwarded, pending.await())
        assertTrue(learning.learningOn)
        assertEquals(1, audioSource.prefetches.size)
    }

    @Test fun cold_start_other_controller_is_ignored() = test {
        startApp()
        assertEquals(PlayCommandResult.Ignored, play(PlaySource.OTHER_CONTROLLER))
        assertTrue(logger.logs.toString(), logged("source=OTHER_CONTROLLER ignored: app not ready"))
        catalogRepo.result.complete(CatalogResult.Success(catalog))
        scope.runCurrent()
        // 나중에 다시 처리하지 않는다
        assertFalse(learning.learningOn)
    }

    @Test fun cold_start_load_failure_is_ignored() = test {
        startApp()
        val pending = scope.async { handlePlay(PlaySource.MEDIA_KEY, "com.test.controller") }
        scope.runCurrent()
        assertFalse(pending.isCompleted)
        catalogRepo.result.complete(CatalogResult.Failure("broken"))
        scope.runCurrent()
        assertEquals(PlayCommandResult.Ignored, pending.await())
        assertTrue(session.state.value is SessionState.Failed)
        assertTrue(logger.logs.toString(), logged("ignored: app failed to load"))
    }
}
