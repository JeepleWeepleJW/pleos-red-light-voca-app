# 레드보카(RedVoca) 개발 문서

레드보카 Android 앱(`:app` 모듈 하나)의 구조를 설명하는 문서 모음이다. 기획·리서치 문서는 `docs/`에 따로 있다.

| 문서 | 내용 |
|---|---|
| [architecture.md](architecture.md) | 계층 구조, 의존 규칙, 주요 실행 흐름, 빌드 변형(debug·release) |
| [packages.md](packages.md) | 패키지별 기능과 주요 클래스 |
| [design-patterns.md](design-patterns.md) | 코드에 쓰인 설계 패턴과 위치 |
| [libraries.md](libraries.md) | 사용 라이브러리·SDK, 버전, 쓰는 곳 |

## 한눈에 보기

- **앱 성격**: Pleos Connect(AAOS) 차량용 미디어 앱. 정차가 확정될 때만 영단어 한 턴("영어 → 뜻 → 영어")을 재생한다.
- **언어·UI**: Kotlin, Jetpack Compose(Material 3, 다크 고정 테마)
- **아키텍처**: `core` / `domain` / `data` / `presentation` / `di` 다섯 계층의 클린 아키텍처. 의존 방향은 단위 테스트(`LayerDependencyTest`)로 강제한다.
- **핵심 도메인**: 정차 판정 상태 기계(`StopGate`) + 학습 세션 상태 기계(`LearningController`)
- **재생**: Media3 `MediaLibraryService` + 재생 관문 플레이어(`GatedPlayer`, `SimpleBasePlayer`가 ExoPlayer를 감쌈)
- **DI**: Hilt. domain은 Hilt를 모르고, `di` 모듈이 조립한다.
- **빌드 변형**: debug는 차량 시뮬레이터·디버그 패널·adb 주입 리시버를 포함하고, release는 실제 차량 입력만 쓴다.

## 기준

- 기준 코드: `feature/jiwun` 브랜치, 2026-11-14 작성
- 패키지 루트: `com.stai.pleos_red_light_voca_app` (문서에서는 생략)
- 코드가 바뀌면 이 문서도 함께 고친다. 특히 계층 규칙은 `app/src/test/.../LayerDependencyTest.kt`, `LayerScanner.kt`가 원본이다.
