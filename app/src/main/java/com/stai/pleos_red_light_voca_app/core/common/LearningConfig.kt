package com.stai.pleos_red_light_voca_app.core.common

/**
 * 조정 가능한 모든 상수를 모은 곳. (TechSpec 3장 초기값, ADR-002 읽기 순서)
 * 공식 규정값이 아니며 에뮬레이터·실차 테스트 후 조정한다.
 *
 * 경계 규칙: "이상"·"이하"는 경계값 포함, "초과"는 미포함.
 */
data class LearningConfig(
    /** 감지 활성(무장): D단에서 이 속도 이상. km/h */
    val armSpeedKmh: Float = 8f,
    /** 감지 활성: 위 조건이 이 시간 동안 연속. ms (단조 시계) */
    val armHoldMs: Long = 3_000L,
    /** 정차 후보·확정: D단에서 이 속도 이하. km/h */
    val stopSpeedKmh: Float = 0.5f,
    /** 정차 확정: 위 조건이 이 시간 동안 연속. ms (단조 시계) */
    val stopHoldMs: Long = 3_000L,
    /** 이동 판정: 이 속도 초과를 받는 즉시 재생 중단. (stopSpeedKmh, moveSpeedKmh] 구간은 새 재생만 금지. km/h */
    val moveSpeedKmh: Float = 1.0f,
    /** 챕터 오디오에서 단어와 뜻 사이 무음 길이. ms */
    val chapterSilenceMs: Long = 600L,
    /** 영어 단어 음성 조각 언어 태그(음원 `<chapterId>.en.wav`) */
    val englishLanguageTag: String = "en-US",
    /** 한국어 뜻 음성 조각 언어 태그(음원 `<chapterId>.ko.wav`) */
    val koreanLanguageTag: String = "ko-KR",
) {
    init {
        require(stopSpeedKmh < moveSpeedKmh) { "stopSpeedKmh must be < moveSpeedKmh" }
        require(moveSpeedKmh < armSpeedKmh) { "moveSpeedKmh must be < armSpeedKmh" }
        require(armHoldMs > 0 && stopHoldMs > 0) { "hold times must be positive" }
        require(chapterSilenceMs >= 0) { "chapterSilenceMs must be >= 0" }
        require(englishLanguageTag != koreanLanguageTag) { "language tags must differ (one audio file per language)" }
    }

    companion object {
        /** 로그 태그. `adb logcat -s VocaApp` */
        const val LOG_TAG: String = "VocaApp"
        /** catalog.json 위치 (assets 기준) */
        const val CATALOG_ASSET_PATH: String = "content/catalog.json"
        /** 지원하는 catalog.json schemaVersion */
        const val CATALOG_SCHEMA_VERSION: Int = 2
    }
}
