package com.stai.pleos_red_light_voca_app.domain.session

import com.stai.pleos_red_light_voca_app.core.common.CoroutineScheduler
import com.stai.pleos_red_light_voca_app.core.common.LearningConfig
import com.stai.pleos_red_light_voca_app.core.common.Logger
import com.stai.pleos_red_light_voca_app.core.common.MonotonicClock
import com.stai.pleos_red_light_voca_app.core.entity.SignalFailure
import com.stai.pleos_red_light_voca_app.core.entity.VehicleEvent
import com.stai.pleos_red_light_voca_app.core.entity.VehicleSignal
import com.stai.pleos_red_light_voca_app.domain.learning.LearningController
import com.stai.pleos_red_light_voca_app.domain.learning.LearningState
import com.stai.pleos_red_light_voca_app.domain.learning.PlayerControl
import com.stai.pleos_red_light_voca_app.domain.learning.StartResult
import com.stai.pleos_red_light_voca_app.domain.repository.CatalogRepository
import com.stai.pleos_red_light_voca_app.domain.repository.CatalogResult
import com.stai.pleos_red_light_voca_app.domain.repository.ProgressRepository
import com.stai.pleos_red_light_voca_app.core.entity.Chapter
import com.stai.pleos_red_light_voca_app.domain.speech.ChapterAudioResult
import com.stai.pleos_red_light_voca_app.domain.source.AudioAvailability
import com.stai.pleos_red_light_voca_app.domain.source.ChapterAudioSource
import com.stai.pleos_red_light_voca_app.domain.source.VehicleStateSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 프로세스 단위 학습 세션. 화면과 미디어 서비스가 domain UseCase를 거쳐 같은
 * [LearningController]를 공유한다. 모든 호출은 메인 스레드에서 한다([scope]는 메인 디스패처).
 *
 * [start] 시: 카탈로그 로드 → 컨트롤러 생성·진도 복원(학습 OFF) → 차량 입력 수집 → 음원 확인.
 * 학습이 OFF로 시작하므로 앱 시작만으로는 재생하지 않는다.
 *
 * @param vehicleSourceFactory 차량 입력 수집을 (다시) 시작할 때마다 새 소스를 만든다.
 */
class AppSession(
    private val catalogRepository: CatalogRepository,
    private val progressRepository: ProgressRepository,
    private val audioSource: ChapterAudioSource,
    private val vehicleSourceFactory: VehicleStateSourceFactory,
    private val clock: MonotonicClock,
    private val scope: CoroutineScope,
    private val config: LearningConfig,
    private val logger: Logger,
) {
    private val playerRelay = PlayerControlRelay(logger)

    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _audio = MutableStateFlow<AudioStatus>(AudioStatus.Checking)
    val audio: StateFlow<AudioStatus> = _audio.asStateFlow()

    private var started = false
    private var vehicleJob: Job? = null
    private var audioJob: Job? = null
    private var prefetchJob: Job? = null

    /** [state]가 Ready가 되기 직전에 정해지고 이후 바뀌지 않는다. */
    private var controller: LearningController? = null

    /** 앱 준비(Ready) 전이면 null. domain UseCase만 쓴다(presentation은 UseCase를 거친다). */
    internal val controllerOrNull: LearningController?
        get() = if (state.value is SessionState.Ready) controller else null

    /** 학습 상태. 세션이 Ready가 된 뒤에만 내보낸다(로딩 실패면 내보내지 않는다). */
    val learningState: Flow<LearningState> = flow {
        state.first { it is SessionState.Ready }
        controller?.let { emitAll(it.state) }
    }

    /** 앱(프로세스) 시작 시 한 번 부른다. 두 번째 호출부터는 무시한다. */
    fun start() {
        if (started) return
        started = true
        scope.launch { load() }
    }

    private suspend fun load() {
        logger.i("app start: loading catalog (learning stays OFF, no playback)")
        val catalog = when (val r = catalogRepository.load()) {
            is CatalogResult.Success -> r.catalog
            is CatalogResult.Failure -> {
                logger.w("catalog load failed: ${r.reason}")
                _state.value = SessionState.Failed(r.reason)
                return
            }
        }
        val controller = LearningController(
            catalog = catalog,
            progressRepository = progressRepository,
            player = playerRelay,
            clock = clock,
            scheduler = CoroutineScheduler(scope),
            scope = scope,
            config = config,
            logger = logger,
        )
        controller.restore()
        this.controller = controller
        _state.value = SessionState.Ready(catalog)
        restartVehicle()
        checkAudio()
    }

    /** 차량 입력 수집을 (다시) 시작한다. 권한을 새로 받았을 때 화면이 호출한다. */
    fun restartVehicle() {
        val controller = controllerOrNull ?: return
        val restarting = vehicleJob != null
        vehicleJob?.cancel()
        logger.i("vehicle source (re)start")
        if (restarting) {
            // 입력원 재시작 = 단절. 이전 판정 이력이 새 수집으로 이어지지 않게 초기화시킨다.
            controller.onVehicleEvent(VehicleEvent.SignalFailed(VehicleSignal.SPEED, SignalFailure.DISCONNECTED))
            controller.onVehicleEvent(VehicleEvent.SignalFailed(VehicleSignal.GEAR, SignalFailure.DISCONNECTED))
        }
        val source = vehicleSourceFactory.create()
        vehicleJob = scope.launch {
            source.events().collect { controller.onVehicleEvent(it) }
        }
    }

    /** 카탈로그 전체 챕터 음원 확인(앱 시작·학습 시작 때). 결과를 화면과 로그에 남긴다. 카탈로그 준비 전이면 무시한다. */
    private fun checkAudio() {
        val ready = state.value as? SessionState.Ready ?: return
        if (audioJob?.isActive == true) return
        _audio.value = AudioStatus.Checking
        audioJob = scope.launch {
            val status = when (val a = audioSource.checkAvailability(ready.catalog.contents)) {
                is AudioAvailability.Checked -> AudioStatus.Checked(a.missing)
                is AudioAvailability.Error -> AudioStatus.Unavailable("음원 확인 오류: ${a.message}")
            }
            _audio.value = status
            logger.i("audio status: ${describe(status)}")
        }
    }

    /** 화면 문구: 콘텐츠 제목과 개수만(경로는 로그에만). */
    private fun userRefusal(s: AudioStatus, contentId: String): String = when (s) {
        is AudioStatus.Checked -> {
            val title = (state.value as? SessionState.Ready)?.catalog?.content(contentId)?.title ?: contentId
            "음원이 준비되지 않아 학습을 시작할 수 없습니다: $title (문제 파일 ${s.missing[contentId].orEmpty().size}개)"
        }
        else -> s.refusalFor(contentId)
    }

    private fun describe(s: AudioStatus): String = when (s) {
        AudioStatus.Checking -> "Checking"
        is AudioStatus.Unavailable -> "Unavailable(${s.message})"
        is AudioStatus.Checked ->
            if (s.missing.isEmpty()) "Ready (all contents)"
            else "Missing " + s.missing.entries.joinToString { (id, files) -> "$id=${files.size} files (e.g. ${files.first()})" }
    }

    /** 화면의 "학습 시작". 그 콘텐츠의 음원이 모두 있지 않으면 시작하지 않는다. 재생은 시작하지 않는다. */
    suspend fun startLearning(contentId: String): StartOutcome {
        val controller = controllerOrNull ?: return StartOutcome.Refused("앱 준비 중")
        if (!_audio.value.isReadyFor(contentId)) {
            audioJob?.join()
            if (!_audio.value.isReadyFor(contentId)) {
                checkAudio()
                audioJob?.join()
            }
        }
        val audio = _audio.value
        if (!audio.isReadyFor(contentId)) {
            logger.w("startLearning refused: ${audio.refusalFor(contentId)}")
            return StartOutcome.Refused(userRefusal(audio, contentId))
        }
        return when (val r = controller.startLearning(contentId)) {
            StartResult.Started -> {
                prefetchActiveContent()
                StartOutcome.Started
            }
            StartResult.AlreadyOn -> StartOutcome.Refused("이미 학습 중")
            is StartResult.Disabled -> StartOutcome.Refused("차량 신호 사용 불가: ${r.reason.blocked}")
            StartResult.UnknownContent -> StartOutcome.Refused("콘텐츠를 찾을 수 없음")
        }
    }

    /**
     * 콜드 스타트 직후 들어온 Play 키를 버리지 않도록, 카탈로그 로딩과 음원 확인이 끝날 때까지 기다린다.
     * 카탈로그 로딩 실패면 null.
     */
    suspend fun awaitReadyForCommand(): Boolean {
        state.first { it !is SessionState.Loading } as? SessionState.Ready ?: return false
        audio.first { it !is AudioStatus.Checking }
        return true
    }

    fun stopLearning() {
        controllerOrNull?.stopLearning()
    }

    /** 학습이 켜진 직후(화면 또는 Play 명령) 콘텐츠 분량을 미리 병합한다. */
    fun prefetchActiveContent() {
        val ready = state.value as? SessionState.Ready ?: return
        val controller = controller ?: return
        val contentId = controller.state.value.contentId ?: return
        val content = ready.catalog.content(contentId) ?: return
        // 현재(다음에 재생할) 챕터부터 병합한다. 재생 시점 준비와 겹치면 음원 소스가 같은 챕터를 한 번만 병합한다.
        val current = controller.state.value.currentChapterId
        val start = content.chapters.indexOfFirst { it.chapterId == current }.coerceAtLeast(0)
        val ordered = content.chapters.drop(start) + content.chapters.take(start)
        prefetchJob?.cancel()
        prefetchJob = scope.launch { audioSource.prefetch(content.contentId, ordered) }
    }

    /** 재생 직전 챕터 음성(WAV)을 준비한다. 미리 병합해 두었으면 그것을 쓴다. */
    suspend fun chapterAudio(contentId: String, chapter: Chapter): ChapterAudioResult =
        audioSource.chapterAudio(contentId, chapter)

    /** 컨트롤러의 정지·자동 재생 요청을 받을 플레이어를 붙인다(서비스가 플레이어를 만든 뒤). 이미 정차 확정 중이면 바로 재생한다. */
    fun attachPlayer(player: PlayerControl) {
        playerRelay.target = player
        controllerOrNull?.tryAutoPlay()
    }

    /** [player]가 붙어 있을 때만 뗀다(서비스 종료 시). */
    fun detachPlayer(player: PlayerControl) {
        if (playerRelay.target === player) playerRelay.target = null
    }
}
