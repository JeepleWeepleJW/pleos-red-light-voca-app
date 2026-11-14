# 설계 패턴

코드에 실제로 쓰인 패턴과 위치를 정리한다. 패키지 루트 `com.stai.pleos_red_light_voca_app`은 생략한다.

## 1. 아키텍처 패턴

### 1.1 클린 아키텍처 (계층 + 의존 역전)

- `core` / `domain` / `data` / `presentation` / `di`. domain이 인터페이스(`CatalogRepository`, `ProgressRepository`, `VehicleStateSource`, `ChapterAudioSource`)를 정의하고 data가 구현한다.
- 의존 방향은 문서 규약이 아니라 **아키텍처 적합성 테스트**(`LayerDependencyTest` + `LayerScanner`)로 강제한다. 소스를 스캔해 import·완전한 이름 참조·typealias 우회까지 잡는다. 자세한 규칙은 [architecture.md](architecture.md#3-의존-규칙).

### 1.2 단방향 데이터 흐름 (MVI 형태)

- `LearningAction`(사용자 의도, sealed) → `LearningViewModel.onAction()` → UseCase
- 상태: `StateFlow<LearningUiState>`(data class), 일회성 효과: `Channel<LearningEvent>`(sealed) → `receiveAsFlow()`
- 이름·형태 규칙(`…Action`·`…Event`는 sealed interface, `…UiState`는 data class)도 `LayerDependencyTest`가 검사한다.

### 1.3 프로세스 단위 세션 (Facade)

- `domain.session.AppSession`이 카탈로그·진도·음원·차량 입력·`LearningController`를 묶는 하나의 진입점이다. 화면과 미디어 서비스는 UseCase를 거쳐 같은 세션을 공유한다.
- presentation은 `AppSession`·`LearningController`를 직접 보지 못한다(허용 목록 밖).

## 2. 도메인 패턴

### 2.1 상태 기계 (State Machine)

| 위치 | 상태 |
|---|---|
| `domain.gate.StopGate` | `GatePhase` 8개: 주행 확인 → 감지 → 정차 확인 → 확정/서행 → 사용 |
| `domain.learning.LearningController` | 학습 ON/OFF × `PlaybackPhase`(`IDLE`/`PREPARING`/`PLAYING`) × 차단 신호 |
| `data.vehicle.GearLinkMonitor` | `UNKNOWN`/`HEALTHY`/`FAILED`/`BLOCKED` 기어 연결 상태 |

전이는 한 스레드(메인)에서만 일어나 락이 필요 없고, 상태 변화는 `StateFlow`나 콜백(`onPhaseChanged`)으로 알린다.

### 2.2 UseCase (Interactor)

- `domain.usecase.*UseCase`: 한 가지 일만 하는 얇은 클래스, `operator fun invoke`.
- domain을 플랫폼과 떼어 두려고 `@Inject`를 붙이지 않고 `di/UseCaseModule`에서 `@Provides`로 만든다. 생성자 속성은 `private val`만 허용한다(테스트로 강제).
- 대부분은 위임만 하고, 판단이 있는 것은 `HandlePlayCommandUseCase`(콜드 스타트 대기 → 출처 게이트 → Play 판정)다.

### 2.3 Repository

- 인터페이스: `domain.repository.CatalogRepository`, `ProgressRepository`
- 구현: `data.catalog.CatalogRepositoryImpl`(+ `CatalogLocalDataSource`), `data.progress.ProgressRepositoryImpl`(DataStore)
- 구현 이름은 `…Impl`(테스트로 강제).

### 2.4 Policy / 순수 규칙 객체 (Strategy 형태)

판단 규칙을 상태 없는 `object`로 떼어 낸다.

| 객체 | 규칙 |
|---|---|
| `domain.playback.PlaySourcePolicy` | Play 출처 × 학습 ON × 음원 준비 → `PlayGate` |
| `presentation.playback.PlaySourceClassifier` | 컨트롤러 패키지·연결 힌트 → `PlaySource` |
| `presentation.playback.PlaybackRules` | 오디오 포커스 상실 대응, 완료 판정, 처음부터 재생 순서 |
| `domain.speech.ChapterScript` | 챕터 읽기 순서 |

### 2.5 티켓 (Token) 패턴

- `LearningController`가 Play를 허가할 때 `PlayTicket(id, contentId, chapterId)`을 발급한다.
- `GatedPlayer`는 음원 준비(비동기) 뒤 실제 `play()` 직전에 `ValidatePlayTicketUseCase`로 티켓이 아직 유효한지 다시 확인한다. 그 사이 출발·기어 이탈·학습 종료가 있었으면 재생하지 않는다.
- 재생 보고(`PlaybackReport`)도 티켓으로 대조해, 늦게 도착한 이전 아이템의 이벤트를 무시한다.

### 2.6 결과 타입 (Sealed Result)

예외 대신 sealed 타입으로 결과를 돌려준다: `CatalogResult`, `StartResult`, `StartOutcome`, `PlayDecision`, `PlayCommandResult`, `ChapterAudioResult`, `AudioAvailability`, `SessionState`, `AudioStatus`. `CatalogParser`는 예외를 밖으로 던지지 않는다.

## 3. 경계와 대체

### 3.1 Port / Adapter (경계 인터페이스)

| 경계(Port) | 앱 구현(Adapter) | 테스트·debug 대체 |
|---|---|---|
| `core.common.Logger` | `data.log.TimberLogger` | `Logger.NONE` |
| `core.common.MonotonicClock` | `data.time.SystemMonotonicClock` | 가상 시간 |
| `core.common.Scheduler` | `CoroutineScheduler`(앱 스코프) | `CoroutineScheduler`(`TestScope`) |
| `domain.source.VehicleStateSource` | `data.vehicle.VehicleStateSourceImpl` | `FakeVehicleStateSource`, `SwitchingVehicleStateSource` |
| `domain.source.ChapterAudioSource` | `data.audio.AssetChapterAudioSource` | 테스트 가짜 |
| `domain.learning.PlayerControl` | `presentation.playback.GatedPlayer` | 테스트 가짜 |
| `data.audio.AudioAssets` | `AndroidAudioAssets` | 메모리 구현 |
| `presentation.playback.StartTarget` | ExoPlayer 대상(`GatedPlayer.exoTarget`) | 기록용 가짜 |

### 3.2 Factory

- `domain.source.VehicleStateSourceFactory`(`fun interface`): 차량 수집을 다시 시작할 때마다 새 소스를 만든다(권한을 새로 받았을 때 등).
- 실제 팩토리는 `@RealVehicleSource`로 제공하고, 빌드 변형별 `VariantModule`이 그것을 받아 앱이 쓸 팩토리를 만든다.

### 3.3 Relay (Proxy)

- `domain.session.PlayerControlRelay`: `LearningController`는 생성 시 플레이어를 하나 받지만, 실제 플레이어(서비스)는 나중에 생기고 사라진다. 릴레이가 그 사이에서 지금 붙은 플레이어로 요청을 넘긴다(`AttachPlayerUseCase`/`DetachPlayerUseCase`).

### 3.4 Gatekeeper 플레이어 (Proxy / Decorator)

- `presentation.playback.GatedPlayer`는 Media3 `SimpleBasePlayer`를 상속해 MediaSession에 자신만 노출하고, 실제 `ExoPlayer`는 감춘다.
- 모든 Play 명령(`handleSetPlayWhenReady`)과 미디어 키를 관문에 통과시키고, 플레이리스트 변경(`setMediaItems`·`prepare`·`seek`)은 무시해 항상 "현재 챕터 1개"로 보인다.

### 3.5 합성·전환 (Composite / Switch)

- `VehicleStateSourceImpl`: 속도(`CarSpeedDataSource`)와 기어(`PleosGearDataSource`) Flow를 `merge`.
- `SwitchingVehicleStateSource`(debug): 모드 `StateFlow`에 따라 실제/가짜 소스를 바꿔 끼운다.

## 4. 테스트 가능성을 위한 패턴

### 4.1 Humble Object (얇은 플랫폼 껍데기 + 순수 로직)

Android·SDK 콜백을 받는 클래스는 얇게 두고, 판단은 순수 Kotlin으로 떼어 JVM 단위 테스트로 고정한다.

| 얇은 껍데기 | 순수 로직 |
|---|---|
| `PleosGearDataSource` | `GearLinkMonitor`, `VehicleMapping.kt`의 `mapGearName`·`classifyPleosFailure`·`isPleosConnectionError` |
| `CarSpeedDataSource` | `VehicleMapping.kt`의 `mapSpeedStatus`, `metersPerSecondToKmh` |
| `GatedPlayer` | `PlaybackRules`, `PlaySourceClassifier`, `MediaIds` |
| `LearningContent`(Compose) | `LearningCard.of(state)`, `ListenButton.of(...)` |
| `AppMediaController` | `AppMediaController.dispatch` |
| `AssetChapterAudioSource` | `WavCodec`, `ChapterAudioAssembler`, `ChapterAudioCacheKey`, `AudioAssetPaths` |

### 4.2 Presentation Model (표시 모델)

- `LearningCard`, `ListenButton`은 `LearningState` → 화면 문구·아이콘·버튼 활성을 정하는 순수 data class다. Composable은 그리기만 한다.
- 재생 허가는 표시 모델이 하지 않고 domain 관문이 한다(버튼 비활성은 안내일 뿐).

## 5. DI 패턴 (Hilt)

| 패턴 | 위치 |
|---|---|
| 모듈별 조립 | `AppModule`(프로세스 객체), `DataModule`(구현 ↔ 인터페이스), `UseCaseModule`(UseCase) |
| 한정자(Qualifier) | `@ApplicationScope`(presentation), `@RealVehicleSource`(di) |
| 빌드 변형 대체 | `di/VariantModule`을 debug·release 소스셋에 같은 이름으로 따로 둔다 |
| 파라미터 객체 | `GatedPlayerUseCases`, `PlaybackServiceUseCases`: 서비스·플레이어가 쓰는 UseCase 묶음을 `@Inject` 생성자 하나로 주입 |
| EntryPoint | `VehicleSimulatorEntryPoint`: Composable(`DebugPanel`)에서 그래프 객체를 꺼냄 |
| 프레임워크 진입점 | `@HiltAndroidApp`, `@AndroidEntryPoint`(Activity·Service·Receiver), `@HiltViewModel` |

## 6. 동시성·자원 패턴

| 패턴 | 위치 | 목적 |
|---|---|---|
| 단일 스레드 한정(Thread confinement) | `@ApplicationScope` = `Dispatchers.Main.immediate`, 세션·컨트롤러·게이트 | 락 없는 상태 관리 |
| 직렬화 쓰기 | `LearningController.persist` (`Mutex`) | 진도 저장 순서 보장, 실패는 로그만 |
| Single-flight | `AssetChapterAudioSource.inFlight` (`ConcurrentHashMap<String, CompletableDeferred>`) | 같은 챕터를 동시에 두 번 병합하지 않음 |
| 내용 주소 캐시 | `ChapterAudioCacheKey` | 음원·설정이 바뀌면 자동으로 새 파일 |
| 임시 파일 후 교체 | `AssetChapterAudioSource.write` | 캐시 파일을 원자적으로 교체 |
| cold Flow + 콜백 연결 | `CarSpeedDataSource`, `PleosGearDataSource` (`callbackFlow`) | 수집 시작에 구독, 취소에 해제 |
| 콜드 스타트 대기 | `HandlePlayCommandUseCase` → `AppSession.awaitReadyForCommand()` | 앱 로딩 중 누른 미디어 키를 버리지 않음 |
