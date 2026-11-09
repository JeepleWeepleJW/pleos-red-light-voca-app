package com.stai.pleos_red_light_voca_app.presentation.learning

import com.stai.pleos_red_light_voca_app.core.entity.Catalog
import com.stai.pleos_red_light_voca_app.core.entity.Chapter
import com.stai.pleos_red_light_voca_app.core.entity.Content
import com.stai.pleos_red_light_voca_app.core.entity.SignalBlock
import com.stai.pleos_red_light_voca_app.core.entity.VehicleSignal
import com.stai.pleos_red_light_voca_app.domain.gate.GatePhase
import com.stai.pleos_red_light_voca_app.domain.learning.DisabledReason
import com.stai.pleos_red_light_voca_app.domain.learning.LearningState
import com.stai.pleos_red_light_voca_app.domain.learning.PlaybackPhase
import com.stai.pleos_red_light_voca_app.domain.session.AudioStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningCardTest {
    private val catalog = Catalog(
        listOf(
            Content("day1", "Day 1", (1..3).map { Chapter("d1-$it", "w$it", "noun", "뜻$it") }),
            Content("day2", "Day 2", (1..2).map { Chapter("d2-$it", "x$it", "verb", "의미$it") }),
            Content("day3", "Day 3", (1..4).map { Chapter("d3-$it", "y$it", "adj", "풀이$it") }),
        ),
    )
    private val ready = AudioStatus.Checked(emptyMap())

    private fun learning(
        on: Boolean = false,
        contentId: String = "day1",
        chapter: String? = "d1-1",
        completed: Int = 0,
        total: Int = 3,
        byContent: Map<String, Int> = emptyMap(),
        gate: GatePhase = GatePhase.INACTIVE,
        playback: PlaybackPhase = PlaybackPhase.IDLE,
        reason: DisabledReason? = null,
    ) = LearningState(
        learningOn = on, contentId = contentId, currentChapterId = chapter, completedChapters = completed,
        totalChapters = total, completedByContent = byContent, gatePhase = gate, playback = playback,
        disabledReason = reason,
    )

    private fun card(
        s: LearningState,
        audio: AudioStatus = ready,
        refused: String? = null,
        cat: Catalog? = catalog,
    ) = LearningCard.of(LearningUiState(isLoading = false, catalog = cat, learning = s, audio = audio, startRefusedMessage = refused))

    private fun onCard(gate: GatePhase, playback: PlaybackPhase = PlaybackPhase.IDLE, audio: AudioStatus = ready) =
        card(learning(on = true, gate = gate, playback = playback), audio)

    private val doneState get() = learning(chapter = null, completed = 3, byContent = mapOf("day1" to 3))

    // ---- 카탈로그 없음 ----

    @Test fun loading_without_catalog() {
        val c = LearningCard.of(LearningUiState())
        assertEquals(StatusLine("불러오는 중", StatusIcon.HOURGLASS, StatusTone.WAIT), c.status)
        assertNull(c.header)
        assertNull(c.picker)
        assertNull(c.nowPlaying)
        assertNull(c.secondary)
        assertFalse(c.primary.enabled)
    }

    @Test fun load_error_without_catalog() {
        val c = LearningCard.of(LearningUiState(isLoading = false, loadErrorMessage = "catalog.json 없음"))
        assertEquals("catalog.json 없음", c.status.text)
        assertEquals(StatusIcon.WARNING, c.status.icon)
        assertEquals(2, c.status.maxLines)
        assertNull(c.header)
        assertNull(c.picker)
        assertFalse(c.primary.enabled)
    }

    // ---- 학습 OFF ----

    @Test fun off_default() {
        val c = card(learning(completed = 1, byContent = mapOf("day1" to 1)))
        assertEquals(StatusLine("학습 꺼짐", StatusIcon.TOGGLE_OFF, StatusTone.OFF), c.status)
        assertFalse(c.status.strong)
        assertEquals(PrimaryButton("학습 시작", ButtonIcon.PLAY_CIRCLE, PrimaryAction.Start), c.primary)
        assertTrue(c.primary.enabled)
        assertNull(c.secondary)
        assertNull(c.nowPlaying)
        val h = c.header!!
        assertEquals("Day 1", h.title)
        assertEquals(3, h.chapterCount)
        assertEquals("진도: 1/3", h.progressText)
        assertEquals(1f / 3, h.progressFraction, 0.0001f)
    }

    @Test fun off_audio_checking_disables_start() {
        val c = card(learning(), AudioStatus.Checking)
        assertEquals(StatusLine("음원을 확인하는 중입니다", StatusIcon.HOURGLASS, StatusTone.WAIT), c.status)
        assertEquals("학습 시작", c.primary.label)
        assertFalse(c.primary.enabled)
    }

    @Test fun off_missing_audio_of_this_content_blocks_start() {
        val c = card(learning(), AudioStatus.Checked(mapOf("day1" to listOf("audio/day1/d1-1.en.wav"))))
        assertEquals(StatusLine("음원이 준비되지 않아 들을 수 없습니다", StatusIcon.WARNING, StatusTone.WARN, strong = true), c.status)
        assertEquals("학습 시작", c.primary.label)
        assertFalse(c.primary.enabled)
    }

    @Test fun off_missing_audio_of_other_content_does_not_block() {
        val c = card(learning(), AudioStatus.Checked(mapOf("day2" to listOf("p"))))
        assertEquals("학습 꺼짐", c.status.text)
        assertTrue(c.primary.enabled)
    }

    @Test fun off_audio_unavailable_blocks_start() {
        val c = card(learning(), AudioStatus.Unavailable("io"))
        assertEquals("음원이 준비되지 않아 들을 수 없습니다", c.status.text)
        assertEquals(StatusIcon.WARNING, c.status.icon)
        assertFalse(c.primary.enabled)
    }

    @Test fun off_start_refused_message_has_top_priority() {
        val reason = DisabledReason(mapOf(VehicleSignal.SPEED to SignalBlock.UNSUPPORTED))
        val c = card(learning(reason = reason), AudioStatus.Checking, refused = "시작할 수 없습니다: Day 1")
        assertEquals("시작할 수 없습니다: Day 1", c.status.text)
        assertEquals(StatusIcon.WARNING, c.status.icon)
        assertEquals(StatusTone.WARN, c.status.tone)
        assertEquals(2, c.status.maxLines)
    }

    @Test fun off_disabled_reason_lists_signals() {
        val one = card(learning(reason = DisabledReason(mapOf(VehicleSignal.SPEED to SignalBlock.UNSUPPORTED))))
        assertEquals("학습 불가: SPEED - UNSUPPORTED", one.status.text)
        assertEquals(StatusIcon.WARNING, one.status.icon)
        assertFalse(one.primary.enabled)

        val two = card(
            learning(
                reason = DisabledReason(
                    linkedMapOf(VehicleSignal.SPEED to SignalBlock.UNSUPPORTED, VehicleSignal.GEAR to SignalBlock.PERMISSION_DENIED),
                ),
            ),
        )
        assertEquals("학습 불가: SPEED - UNSUPPORTED, GEAR - PERMISSION_DENIED", two.status.text)
        assertFalse(two.primary.enabled)
    }

    // ---- 완료 콘텐츠 ----

    @Test fun completed_content_offers_restart_and_next_content() {
        val c = card(doneState)
        assertEquals(StatusLine("콘텐츠 완료", StatusIcon.CHECK_CIRCLE, StatusTone.DONE, strong = true), c.status)
        assertEquals(PrimaryButton("처음부터 다시", ButtonIcon.REPLAY, PrimaryAction.Start), c.primary)
        assertTrue(c.primary.enabled)
        val next = c.secondary as SecondaryButton.NextContent
        assertEquals("day2", next.contentId)
        assertEquals("Day 2", next.title)
        assertEquals("다음 콘텐츠: Day 2", next.label)
        assertEquals(ButtonIcon.SKIP_NEXT, next.icon)
    }

    @Test fun completed_last_content_has_no_next() {
        val s = learning(contentId = "day3", chapter = null, completed = 4, total = 4, byContent = mapOf("day3" to 4))
        val c = card(s)
        assertEquals("콘텐츠 완료", c.status.text)
        assertEquals("처음부터 다시", c.primary.label)
        assertNull(c.secondary)
    }

    @Test fun completed_with_missing_audio_shows_audio_not_ready() {
        val c = card(doneState, AudioStatus.Checked(mapOf("day1" to listOf("p"))))
        assertEquals("음원이 준비되지 않아 들을 수 없습니다", c.status.text)
        assertEquals(StatusIcon.WARNING, c.status.icon)
        assertEquals("처음부터 다시", c.primary.label)
        assertEquals(ButtonIcon.REPLAY, c.primary.icon)
        assertFalse(c.primary.enabled)
    }

    // ---- 학습 ON ----

    @Test fun on_armed_is_ready_with_hint() {
        val c = onCard(GatePhase.ARMED)
        assertEquals(StatusLine("지금 들을 수 있어요", StatusIcon.VOLUME_UP, StatusTone.READY, strong = true, hint = "차량 Play 키로도 들을 수 있어요"), c.status)
        assertEquals(PrimaryButton("듣기", ButtonIcon.PLAY_ARROW, PrimaryAction.Listen(ListenCommand.PLAY)), c.primary)
        assertTrue(c.primary.enabled)
        assertEquals(SecondaryButton.StopLearning, c.secondary)
        assertEquals("학습 종료", c.secondary!!.label)
        assertNull(c.nowPlaying)
    }

    @Test fun on_used_waits_for_next_stop() {
        val c = onCard(GatePhase.USED)
        assertEquals(StatusLine("다음 정차에서 들을 수 있어요", StatusIcon.SCHEDULE, StatusTone.WAIT), c.status)
        assertEquals(PrimaryButton("듣기", ButtonIcon.PLAY_ARROW, null), c.primary)
        assertEquals(SecondaryButton.StopLearning, c.secondary)
    }

    @Test fun on_other_phases_wait_for_stop() {
        val phases = listOf(
            GatePhase.INACTIVE, GatePhase.WAITING_DRIVE, GatePhase.DRIVE_CONFIRMING,
            GatePhase.DETECTING, GatePhase.STOP_CONFIRMING, GatePhase.ARMED_CREEP,
        )
        for (g in phases) {
            val c = onCard(g)
            assertEquals(g.name, StatusLine("정차하면 들을 수 있어요", StatusIcon.DIRECTIONS_CAR, StatusTone.WAIT), c.status)
            assertEquals(g.name, PrimaryButton("듣기", ButtonIcon.PLAY_ARROW, null), c.primary)
            assertEquals(g.name, SecondaryButton.StopLearning, c.secondary)
        }
    }

    @Test fun on_playing_shows_now_playing_and_pause() {
        val s = { p: PlaybackPhase ->
            learning(on = true, chapter = "d1-2", completed = 1, gate = GatePhase.USED, playback = p)
        }
        for (p in listOf(PlaybackPhase.PREPARING, PlaybackPhase.PLAYING)) {
            val c = card(s(p), AudioStatus.Checking)
            assertEquals(NowPlaying(2, "w2", "noun", "뜻2"), c.nowPlaying)
            assertEquals(StatusLine("듣는 중 · 2번째 챕터", StatusIcon.GRAPHIC_EQ, StatusTone.PLAYING, strong = true), c.status)
            assertEquals(PrimaryButton("멈춤", ButtonIcon.PAUSE, PrimaryAction.Listen(ListenCommand.PAUSE)), c.primary)
            assertTrue("멈춤은 음원 확인 중에도 활성", c.primary.enabled)
            assertEquals(SecondaryButton.StopLearning, c.secondary)
        }
    }

    @Test fun on_audio_checking_disables_listen() {
        val c = onCard(GatePhase.ARMED, audio = AudioStatus.Checking)
        assertEquals(StatusLine("음원을 확인하는 중입니다", StatusIcon.HOURGLASS, StatusTone.WAIT), c.status)
        assertEquals("듣기", c.primary.label)
        assertFalse(c.primary.enabled)
        assertEquals(SecondaryButton.StopLearning, c.secondary)
    }

    @Test fun on_missing_audio_disables_listen() {
        val c = onCard(GatePhase.ARMED, audio = AudioStatus.Checked(mapOf("day1" to listOf("p"))))
        assertEquals(StatusLine("음원이 준비되지 않아 들을 수 없습니다", StatusIcon.WARNING, StatusTone.WARN, strong = true), c.status)
        assertFalse(c.primary.enabled)
        assertEquals(SecondaryButton.StopLearning, c.secondary)
    }

    // ---- 콘텐츠 고르기 ----

    @Test fun picker_when_learning_off() {
        val s = learning(completed = 1, byContent = mapOf("day1" to 1, "day3" to 4))
        val p = card(s).picker!!
        assertTrue(p.enabled)
        assertNull(p.lockHint)
        assertEquals(2, p.otherCount)
        assertEquals("다른 콘텐츠 고르기 (2개)", p.label)
        assertEquals(listOf("day1", "day2", "day3"), p.rows.map { it.contentId })
        assertEquals(listOf(true, false, false), p.rows.map { it.selected })
        assertEquals(listOf(1, 0, 4), p.rows.map { it.completed })
        assertEquals(listOf("진도: 1/3", "진도: 0/2", "진도: 4/4"), p.rows.map { it.progressText })
        assertEquals(listOf(false, false, true), p.rows.map { it.done })
        assertEquals(listOf("(3챕터)", "(2챕터)", "(4챕터)"), p.rows.map { it.chapterText })
        assertEquals(listOf("Day 1", "Day 2", "Day 3"), p.rows.map { it.title })
    }

    @Test fun picker_is_locked_while_learning_on() {
        val p = onCard(GatePhase.ARMED).picker!!
        assertFalse(p.enabled)
        assertEquals("학습을 끝내면 바꿀 수 있어요", p.lockHint)
        assertEquals(2, p.otherCount)
        assertEquals(3, p.rows.size)
    }

    @Test fun picker_with_single_content_is_disabled() {
        val one = Catalog(listOf(catalog.contents[0]))
        val p = card(learning(), cat = one).picker!!
        assertFalse(p.enabled)
        assertEquals(0, p.otherCount)
        assertEquals("다른 콘텐츠 고르기 (0개)", p.label)
        assertEquals(1, p.rows.size)
        assertTrue(p.rows[0].selected)
    }
}
