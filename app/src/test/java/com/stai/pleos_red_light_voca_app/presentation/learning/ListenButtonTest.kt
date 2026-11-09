package com.stai.pleos_red_light_voca_app.presentation.learning

import com.stai.pleos_red_light_voca_app.domain.gate.GatePhase
import com.stai.pleos_red_light_voca_app.domain.learning.LearningState
import com.stai.pleos_red_light_voca_app.domain.learning.PlaybackPhase
import com.stai.pleos_red_light_voca_app.domain.session.AudioStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenButtonTest {
    private val ready = AudioStatus.Checked(emptyMap())
    private fun lb(s: LearningState, audio: AudioStatus = ready) = ListenButton.of(s, audio)
    private fun on(gate: GatePhase, playback: PlaybackPhase = PlaybackPhase.IDLE, chapter: String? = "c1") =
        LearningState(learningOn = true, contentId = "day1", currentChapterId = chapter, gatePhase = gate, playback = playback)

    @Test fun learning_off_is_disabled() {
        val b = lb(LearningState(learningOn = false, gatePhase = GatePhase.ARMED))
        assertFalse(b.enabled)
        assertNull(b.command)
        assertEquals("듣기", b.label)
    }

    @Test fun armed_sends_play() {
        val b = lb(on(GatePhase.ARMED))
        assertEquals(ListenButton("듣기", ListenCommand.PLAY), b)
        assertTrue(b.enabled)
    }

    @Test fun preparing_or_playing_becomes_pause() {
        for (p in listOf(PlaybackPhase.PREPARING, PlaybackPhase.PLAYING)) {
            val b = lb(on(GatePhase.USED, p))
            assertEquals(ListenButton("멈춤", ListenCommand.PAUSE), b)
            assertTrue(b.enabled)
        }
        // 재생이 끝나면(IDLE) 멈춤이 아님(이번 정차는 이미 사용)
        assertNull(lb(on(GatePhase.USED, PlaybackPhase.IDLE)).command)
    }

    @Test fun other_phases_are_disabled() {
        for (gate in GatePhase.values().filter { it != GatePhase.ARMED }) {
            val b = lb(on(gate))
            assertFalse(gate.name, b.enabled)
            assertNull(gate.name, b.command)
            assertEquals(gate.name, "듣기", b.label)
        }
    }

    @Test fun no_next_chapter_is_disabled() {
        val b = lb(on(GatePhase.ARMED, chapter = null))
        assertFalse(b.enabled)
        assertEquals("듣기", b.label)
    }

    @Test fun audio_checking_or_not_ready_blocks_play_but_not_pause() {
        assertEquals(ListenButton("듣기", null), lb(on(GatePhase.ARMED), AudioStatus.Checking))
        val missing = AudioStatus.Checked(mapOf("day1" to listOf("audio/day1/c1.en.wav")))
        assertEquals(ListenButton("듣기", null), lb(on(GatePhase.ARMED), missing))
        assertEquals(ListenButton("듣기", null), lb(on(GatePhase.ARMED), AudioStatus.Unavailable("x")))
        assertEquals("다른 콘텐츠 누락은 막지 않음", ListenCommand.PLAY, lb(on(GatePhase.ARMED), AudioStatus.Checked(mapOf("x" to listOf("p")))).command)
        assertEquals(ListenCommand.PAUSE, lb(on(GatePhase.USED, PlaybackPhase.PLAYING), AudioStatus.Checking).command)
    }
}
