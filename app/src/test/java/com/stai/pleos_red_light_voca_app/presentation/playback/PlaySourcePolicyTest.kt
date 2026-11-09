package com.stai.pleos_red_light_voca_app.presentation.playback

import com.stai.pleos_red_light_voca_app.domain.playback.PlaySource
import org.junit.Assert.assertEquals
import org.junit.Test

/** 컨트롤러 → Play 출처 분류(presentation). 출처별 허용 규칙은 domain의 같은 이름 테스트. */
class PlaySourcePolicyTest {
    private val own = "com.stai.pleos_red_light_voca_app"

    @Test fun self_requires_debug_hint_and_debuggable_build() {
        assertEquals(PlaySource.SELF, PlaySourceClassifier.classifyController(own, hasDebugHint = true, debuggableBuild = true))
        // release 빌드: 힌트를 위조해도 SELF가 아님
        assertEquals(PlaySource.OTHER_CONTROLLER, PlaySourceClassifier.classifyController("evil.app", hasDebugHint = true, debuggableBuild = false))
        // 미디어 알림 컨트롤러(System UI 명령이 바뀌어 들어옴, 패키지 = 이 앱)는 SELF가 아님(적대적 리뷰 MH1)
        assertEquals(PlaySource.OTHER_CONTROLLER, PlaySourceClassifier.classifyController(own, hasDebugHint = false, debuggableBuild = true))
    }

    @Test fun app_ui_requires_hint_and_own_package_in_any_build() {
        fun c(pkg: String?, hint: Boolean, debuggable: Boolean) =
            PlaySourceClassifier.classifyController(pkg, hasDebugHint = false, debuggableBuild = debuggable, hasAppUiHint = hint, ownPackage = own)
        assertEquals(PlaySource.APP_UI, c(own, hint = true, debuggable = false))
        assertEquals(PlaySource.APP_UI, c(own, hint = true, debuggable = true))
        // 다른 앱이 힌트를 넣어도 APP_UI가 아님
        assertEquals(PlaySource.OTHER_CONTROLLER, c("evil.app", hint = true, debuggable = false))
        // 힌트 없는 이 앱 패키지(미디어 알림 컨트롤러)는 OTHER
        assertEquals(PlaySource.OTHER_CONTROLLER, c(own, hint = false, debuggable = false))
        // 디버그 힌트가 함께 있으면 debuggable 빌드에서는 SELF가 먼저
        assertEquals(
            PlaySource.SELF,
            PlaySourceClassifier.classifyController(own, hasDebugHint = true, debuggableBuild = true, hasAppUiHint = true, ownPackage = own),
        )
    }

    @Test fun classify_reads_both_hint_keys_through_lookup() {
        fun c(hints: Set<String>, debuggable: Boolean, pkg: String? = own) =
            PlaySourceClassifier.classify(pkg, { it in hints }, debuggable, own)
        assertEquals(PlaySource.APP_UI, c(setOf(PlaySourceClassifier.APP_UI_CONTROLLER_HINT), debuggable = false))
        assertEquals(PlaySource.SELF, c(setOf(PlaySourceClassifier.DEBUG_CONTROLLER_HINT), debuggable = true))
        assertEquals(PlaySource.OTHER_CONTROLLER, c(emptySet(), debuggable = true))
        assertEquals(PlaySource.OTHER_CONTROLLER, c(setOf(PlaySourceClassifier.APP_UI_CONTROLLER_HINT), debuggable = false, pkg = "evil.app"))
        assertEquals(PlaySource.SYSTEM_AUTOPLAY, c(emptySet(), debuggable = false, pkg = "com.android.car"))
    }

    @Test fun listen_command_dispatch() {
        val calls = mutableListOf<String>()
        AppMediaController.dispatch(com.stai.pleos_red_light_voca_app.presentation.learning.ListenCommand.PLAY, { calls += "play" }, { calls += "pause" })
        AppMediaController.dispatch(com.stai.pleos_red_light_voca_app.presentation.learning.ListenCommand.PAUSE, { calls += "play" }, { calls += "pause" })
        assertEquals(listOf("play", "pause"), calls)
    }

    @Test fun system_autoplay_and_other() {
        assertEquals(PlaySource.SYSTEM_AUTOPLAY, PlaySourceClassifier.classifyController("com.android.car", false, true))
        assertEquals(PlaySource.SYSTEM_AUTOPLAY, PlaySourceClassifier.classifyController("com.android.car.updatable", false, false))
        assertEquals(PlaySource.OTHER_CONTROLLER, PlaySourceClassifier.classifyController("com.android.car.media", false, false))
        assertEquals(PlaySource.OTHER_CONTROLLER, PlaySourceClassifier.classifyController(null, false, false))
    }
}
