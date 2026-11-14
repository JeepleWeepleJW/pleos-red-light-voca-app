package com.stai.pleos_red_light_voca_app.core

import com.stai.pleos_red_light_voca_app.LayerScanner
import com.stai.pleos_red_light_voca_app.core.common.LearningConfig
import com.stai.pleos_red_light_voca_app.core.common.metersPerSecondToKmh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreBoundaryTest {
    @Test fun speed_conversion_uses_abs_and_3_6() {
        assertEquals(36f, metersPerSecondToKmh(10f), 0.0001f)
        assertEquals(3.6f, metersPerSecondToKmh(-1f), 0.0001f) // 후진 음수
        assertEquals(0f, metersPerSecondToKmh(0f), 0f)
    }

    @Test fun default_thresholds_match_techspec() {
        val c = LearningConfig()
        assertEquals(8f, c.armSpeedKmh)
        assertEquals(3_000L, c.armHoldMs)
        assertEquals(0.5f, c.stopSpeedKmh)
        assertEquals(3_000L, c.stopHoldMs)
        assertEquals(3.0f, c.moveSpeedKmh)
    }

    @Test(expected = IllegalArgumentException::class)
    fun inconsistent_thresholds_are_rejected() {
        LearningConfig(stopSpeedKmh = 2f, moveSpeedKmh = 1f)
    }

    /** 언어 태그로 `.en`·`.ko` 음원을 고르므로 두 태그는 달라야 한다. */
    @Test(expected = IllegalArgumentException::class)
    fun same_language_tags_are_rejected() {
        LearningConfig(englishLanguageTag = "ko-KR", koreanLanguageTag = "ko-KR")
    }

    /** core 플랫폼 기준은 LayerScanner 하나만 쓴다(LayerDependencyTest `core_and_domain_have_no_platform_imports`와 같은 기준). */
    @Test fun core_sources_do_not_import_android() {
        val core = LayerScanner.scanProject().filter { it.layer == "core" }
        assertTrue("no core sources found", core.isNotEmpty())
        val bad = LayerScanner.coreOrDomainUsesPlatform(core)
        assertTrue("platform references in core:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
