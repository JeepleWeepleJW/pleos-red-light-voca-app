package com.stai.pleos_red_light_voca_app.presentation.playback

import androidx.media3.common.Player
import com.stai.pleos_red_light_voca_app.domain.learning.PlayTicket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRulesTest {
    @Test fun transient_focus_loss_halts_to_block_auto_resume() {
        assertEquals(
            FocusAction.HALT_AND_REPORT,
            PlaybackRules.onSuppressionReason(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS),
        )
        assertEquals(FocusAction.NONE, PlaybackRules.onSuppressionReason(Player.PLAYBACK_SUPPRESSION_REASON_NONE))
    }

    @Test fun permanent_focus_loss_stops() {
        assertEquals(
            FocusAction.HALT_AND_REPORT,
            PlaybackRules.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS),
        )
        assertEquals(
            FocusAction.NONE,
            PlaybackRules.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST),
        )
        assertEquals(
            FocusAction.NONE,
            PlaybackRules.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS),
        )
    }

    @Test fun ended_counts_only_for_current_ticket_item() {
        val t1 = PlayTicket(1, "c", "a")
        val t2 = PlayTicket(2, "c", "a")
        assertTrue(PlaybackRules.isEndedForTicket(t1, t1))
        assertFalse(PlaybackRules.isEndedForTicket(t1, t2)) // 같은 챕터라도 이전 티켓의 늦은 종료
        assertFalse(PlaybackRules.isEndedForTicket(null, t1))
        assertFalse(PlaybackRules.isEndedForTicket(t1, null))
    }

    @Test fun start_always_from_beginning_in_order() {
        val calls = mutableListOf<String>()
        val target = object : StartTarget<String> {
            override fun setMediaItem(item: String) { calls += "set:$item" }
            override fun prepare() { calls += "prepare" }
            override fun seekTo(positionMs: Long) { calls += "seek:$positionMs" }
            override fun play() { calls += "play" }
        }
        PlaybackRules.startFromBeginning(target, "x")
        assertEquals(listOf("set:x", "prepare", "seek:0", "play"), calls)
    }
}
