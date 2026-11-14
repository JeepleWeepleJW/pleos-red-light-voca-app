# 라이브러리

버전은 `gradle/libs.versions.toml`, 의존성은 `app/build.gradle.kts` 기준이다. 라이브러리 설명은 이 프로젝트에서 쓰는 용도만 적는다.

## 1. 빌드 환경

| 항목 | 값 |
|---|---|
| Android Gradle Plugin | 9.5.1 |
| Kotlin | 2.2.10 (Compose 컴파일러 플러그인 `org.jetbrains.kotlin.plugin.compose`) |
| KSP | 2.3.13 (Hilt 코드 생성) |
| compileSdk / targetSdk / minSdk | 37 / 37 / 24 |
| Java | 11 (`sourceCompatibility`, `targetCompatibility`) |
| 플랫폼 라이브러리 | `useLibrary("android.car")` (AAOS Car API, 매니페스트 `uses-library required=false`) |
| 기기 요구 | `android.hardware.type.automotive` 필수 |
| release 최적화 | `optimization { enable = true }` (`androidx.**`, `kotlin.**`, `kotlinx.**` 범위) |

## 2. 런타임 의존성

| 라이브러리 | 좌표 | 버전 | 용도 | 쓰는 곳 |
|---|---|---|---|---|
| Pleos Vehicle SDK | `ai.pleos.playground:Vehicle` | 2.3.2 | 기어 선택 상태 구독·조회 | `data.vehicle.PleosGearDataSource` |
| AAOS Car API | `android.car` (플랫폼) | 기기 제공 | `CarPropertyManager`의 `PERF_VEHICLE_SPEED` 구독 | `data.vehicle.CarSpeedDataSource` |
| Media3 Session | `androidx.media3:media3-session` | 1.11.1 | `MediaLibraryService`, `MediaLibrarySession`, `MediaController`, `SimpleBasePlayer` | `presentation.playback.*` |
| Media3 ExoPlayer | `androidx.media3:media3-exoplayer` | 1.11.1 | 챕터 WAV 재생, 오디오 포커스(`handleAudioFocus=true`) | `VocaPlaybackService`, `GatedPlayer` |
| DataStore Preferences | `androidx.datastore:datastore-preferences` | 1.3.0 | 진도 저장(`learning_progress`) | `data.progress.ProgressRepositoryImpl` |
| Kotlin Coroutines | `org.jetbrains.kotlinx:kotlinx-coroutines-core` / `-android` | 1.11.0 | `Flow`·`StateFlow`·`Channel`·`Mutex`, 메인 디스패처 | core·domain(core만), data·presentation·di(android 포함) |
| Hilt | `com.google.dagger:hilt-android` (+ `hilt-compiler` KSP) | 2.59.2 | 의존성 주입 | `di`, `@AndroidEntryPoint` 클래스, `@HiltViewModel` |
| Hilt ViewModel Compose | `androidx.hilt:hilt-lifecycle-viewmodel-compose` | 1.3.0 | Composable에서 `hiltViewModel()` | `LearningScreen` |
| Timber | `com.jakewharton.timber:timber` | 5.0.1 | 로그(태그 `VocaApp`) | `data`·`presentation`·`di`만 |
| Compose BOM | `androidx.compose:compose-bom` | 2026.02.01 | Compose 라이브러리 버전 정렬 | — |
| Compose UI / Graphics / Tooling Preview | `androidx.compose.ui:*` | BOM | 화면 그리기 | `presentation.learning`, `presentation.theme` |
| Compose Material 3 | `androidx.compose.material3:material3` | BOM | 다크 테마, 기본 컴포넌트 | `presentation.theme`, `presentation.learning` |
| Activity Compose | `androidx.activity:activity-compose` | 1.8.0 | `setContent`, 권한 요청 런처 | `MainActivity`, `LearningScreen` |
| Lifecycle Runtime KTX | `androidx.lifecycle:lifecycle-runtime-ktx` | 2.9.4 | `ON_RESUME` 관찰 등 | `LearningScreen` |
| Lifecycle ViewModel Compose | `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.9.4 | `ViewModel`, `viewModelScope` | `LearningViewModel` |
| Core KTX | `androidx.core:core-ktx` | 1.10.1 | `ContextCompat`, `IntentCompat`, `toUri` | `LearningScreen`, `VocaPlaybackService` |

Guava의 `ListenableFuture`·`Futures`·`ImmutableList`는 Media3가 함께 가져오는 것을 `GatedPlayer`·`VocaPlaybackService`·`LearningServiceConnection`에서 쓴다(직접 선언한 의존성은 아님).

## 3. 직접 구현해 외부 라이브러리를 쓰지 않은 부분

| 기능 | 구현 | 이유(코드 주석 기준) |
|---|---|---|
| JSON 파싱 | `data.catalog.json.MiniJson` | 순수 Kotlin으로 JVM 단위 테스트, `org.json` 미사용 |
| WAV 디코드·병합·리샘플 | `data.audio.WavCodec`, `ChapterAudioAssembler` | Android 의존 없는 병합, 단위 테스트 |
| 해시 | `AudioAssetPaths.textHash`, `ChapterAudioCacheKey` (SHA-256, JDK `MessageDigest`) | catalog 문구와 음원 일치 확인, 캐시 파일 이름 |

## 4. 테스트 의존성

| 라이브러리 | 버전 | 용도 |
|---|---|---|
| JUnit 4 | 4.13.2 | JVM 단위 테스트(`test`, `testDebug`) |
| kotlinx-coroutines-test | 1.11.0 | `TestScope`·가상 시간으로 `StopGate`·`LearningController` 시간 판정 테스트 |
| AndroidX Test JUnit | 1.1.5 | 계측 테스트 |
| Espresso Core | 3.5.1 | 계측 테스트 |
| Compose UI Test JUnit4 / Manifest | BOM | 계측 테스트(현재 예제만) |
| Compose UI Tooling | BOM | debug 미리보기 |

## 5. 플랫폼 권한 (라이브러리 사용에 필요)

| 권한 | 용도 |
|---|---|
| `android.car.permission.CAR_SPEED` | 속도 구독(위험 권한, 런타임 요청) |
| `pleos.car.permission.CAR_DRIVE` | Pleos 기어 |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Media3 미디어 재생 포그라운드 서비스 |
| `POST_NOTIFICATIONS` | 미디어 알림(API 33 이상 런타임 요청) |

## 6. 저장소

- `google()`, `mavenCentral()`
- Pleos Nexus: `https://nexus-playground.pleos.ai/repository/maven-releases/` (`ai.pleos.playground` 그룹만)
