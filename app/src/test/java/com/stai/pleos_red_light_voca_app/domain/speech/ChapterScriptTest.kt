package com.stai.pleos_red_light_voca_app.domain.speech

import com.stai.pleos_red_light_voca_app.core.entity.Chapter
import org.junit.Assert.assertEquals
import org.junit.Test

/** 챕터 낭독 순서(domain). `core/CoreBoundaryTest`에서 옮겼다. */
class ChapterScriptTest {
    @Test fun chapter_script_order_is_en_silence_ko_silence_en() {
        val script = ChapterScript.of(Chapter("c", "signal", "noun", "신호"))
        assertEquals(
            listOf(
                ChapterSegment.Speech("en-US", "signal"),
                ChapterSegment.Silence(600),
                ChapterSegment.Speech("ko-KR", "신호"),
                ChapterSegment.Silence(600),
                ChapterSegment.Speech("en-US", "signal"),
            ),
            script,
        )
    }
}
