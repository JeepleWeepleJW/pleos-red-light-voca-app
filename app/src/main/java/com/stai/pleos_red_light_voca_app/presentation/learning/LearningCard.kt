package com.stai.pleos_red_light_voca_app.presentation.learning

import com.stai.pleos_red_light_voca_app.core.entity.Catalog
import com.stai.pleos_red_light_voca_app.core.entity.Content
import com.stai.pleos_red_light_voca_app.domain.gate.GatePhase
import com.stai.pleos_red_light_voca_app.domain.learning.LearningState
import com.stai.pleos_red_light_voca_app.domain.learning.PlaybackPhase
import com.stai.pleos_red_light_voca_app.domain.session.AudioStatus

/** 상태 줄 아이콘(Material Symbols Outlined 이름). 그리는 쪽이 drawable로 바꾼다. */
enum class StatusIcon { TOGGLE_OFF, DIRECTIONS_CAR, VOLUME_UP, GRAPHIC_EQ, SCHEDULE, CHECK_CIRCLE, WARNING, HOURGLASS }

/** 상태 줄 색 역할(토큰 status-*). OFF·WAIT는 글자도 text-muted. */
enum class StatusTone { OFF, WAIT, READY, PLAYING, DONE, WARN }

/** 버튼 아이콘(Material Symbols Outlined 이름) */
enum class ButtonIcon { PLAY_CIRCLE, PLAY_ARROW, PAUSE, REPLAY, STOP_CIRCLE, SKIP_NEXT }

/**
 * '지금' 칸 상태 한 줄. [strong]이면 굵게. [maxLines]는 안내·오류처럼 긴 문구만 2줄.
 * [hint]는 상태 아래 보조 한 줄(정차 확정의 '차량 Play 키로도 들을 수 있어요').
 */
data class StatusLine(
    val text: String,
    val icon: StatusIcon,
    val tone: StatusTone,
    val strong: Boolean = false,
    val hint: String? = null,
    val maxLines: Int = 1,
)

/** 재생 중 '지금' 칸에 띄울 단어. [chapterNumber]는 1부터. */
data class NowPlaying(val chapterNumber: Int, val word: String, val partOfSpeech: String, val meaningKo: String)

/** 주 버튼이 누르면 할 일. */
sealed interface PrimaryAction {
    /** 학습 시작(완료 콘텐츠면 진도를 지우고 처음부터 — C6, 동작은 '학습 시작'과 같음) */
    data object Start : PrimaryAction
    /** 듣기·멈춤. 보낼 명령은 ViewModel이 [ListenButton]으로 다시 정한다. */
    data class Listen(val command: ListenCommand) : PrimaryAction
}

/** [action]이 null이면 비활성. */
data class PrimaryButton(val label: String, val icon: ButtonIcon, val action: PrimaryAction?) {
    val enabled: Boolean get() = action != null
}

/** 보조 버튼(오른쪽 1/3). 없으면 자리를 비운다. */
sealed interface SecondaryButton {
    val label: String
    val icon: ButtonIcon

    data object StopLearning : SecondaryButton {
        override val label = "학습 종료"
        override val icon = ButtonIcon.STOP_CIRCLE
    }

    /** D7: 다음 콘텐츠로 선택만 바꾼다(학습은 켜지 않음). */
    data class NextContent(val contentId: String, val title: String) : SecondaryButton {
        override val label = "다음 콘텐츠: $title"
        override val icon = ButtonIcon.SKIP_NEXT
    }
}

/** 카드 제목 줄과 진도 막대. */
data class CardHeader(val title: String, val chapterCount: Int, val completed: Int) {
    val progressText: String get() = "진도: $completed/$chapterCount"
    val progressFraction: Float get() = if (chapterCount == 0) 0f else completed.toFloat() / chapterCount
}

/** '다른 콘텐츠 고르기' 목록 행. */
data class ContentRow(
    val contentId: String,
    val title: String,
    val chapterCount: Int,
    val completed: Int,
    val selected: Boolean,
) {
    val chapterText: String get() = "(${chapterCount}챕터)"
    val progressText: String get() = "진도: $completed/$chapterCount"
    val done: Boolean get() = chapterCount > 0 && completed >= chapterCount
}

/**
 * '다른 콘텐츠 고르기' 줄. [enabled]가 false면 펼칠 수 없다. [lockHint]는 학습 ON이라 잠겼을 때의 안내.
 * [otherCount]는 지금 선택한 것을 뺀 콘텐츠 수.
 */
data class Picker(val otherCount: Int, val enabled: Boolean, val lockHint: String?, val rows: List<ContentRow>) {
    val label: String get() = "다른 콘텐츠 고르기 (${otherCount}개)"
}

/**
 * 학습 화면 B안(이어 하기 카드 + 접힌 목록) 표시 판단(순수 Kotlin). 문구·버튼 활성·아이콘을 여기서 정한다.
 * 기준: `design/learning/LEARN-hifi-B.html`(아트보드 8개)과 사용자 결정(`_workspace/decisions.md`).
 * 재생 허가는 하지 않는다: 버튼은 세션에 명령만 보내고 정차 확정·정차당 1회·음원 판정은 domain 게이트가 한다.
 *
 * @param header null이면 카드 제목 줄을 그리지 않는다(불러오는 중·로드 오류·콘텐츠 없음).
 * @param nowPlaying 재생 중이면 단어. null이면 [status] 한 줄.
 * @param secondary null이면 보조 버튼 자리를 비운다.
 * @param picker null이면 고르기 줄을 그리지 않는다(카탈로그 없음).
 */
data class LearningCard(
    val header: CardHeader?,
    val status: StatusLine,
    val nowPlaying: NowPlaying?,
    val primary: PrimaryButton,
    val secondary: SecondaryButton?,
    val picker: Picker?,
) {
    companion object {
        const val LOADING = "불러오는 중"
        const val OFF = "학습 꺼짐"
        const val WAIT_STOP = "정차하면 들을 수 있어요"
        const val ARMED = "지금 들을 수 있어요"
        const val ARMED_HINT = "차량 Play 키로도 들을 수 있어요"
        const val USED = "다음 정차에서 들을 수 있어요"
        const val DONE = "콘텐츠 완료"
        const val AUDIO_CHECKING = "음원을 확인하는 중입니다"
        const val AUDIO_MISSING = "음원이 준비되지 않아 들을 수 없습니다"
        const val PICKER_LOCKED = "학습을 끝내면 바꿀 수 있어요"

        private val START = PrimaryButton("학습 시작", ButtonIcon.PLAY_CIRCLE, null)

        fun of(ui: LearningUiState): LearningCard {
            val catalog = ui.catalog
            val s = ui.learning
            val content = catalog?.let { c -> s.contentId?.let { c.content(it) } }
            val header = content?.let { CardHeader(it.title, it.chapters.size, s.completedChapters) }
            val picker = catalog?.let { picker(it, s) }

            if (catalog == null) {
                val status = ui.loadErrorMessage?.let { warn(it) }
                    ?: StatusLine(LOADING, StatusIcon.HOURGLASS, StatusTone.WAIT)
                return LearningCard(null, status, null, START, null, null)
            }
            return if (s.learningOn) on(s, ui.audio, content, header, picker)
            else off(ui, s, catalog, content, header, picker)
        }

        private fun warn(text: String) = StatusLine(text, StatusIcon.WARNING, StatusTone.WARN, strong = true, maxLines = 2)

        private fun off(
            ui: LearningUiState,
            s: LearningState,
            catalog: Catalog,
            content: Content?,
            header: CardHeader?,
            picker: Picker?,
        ): LearningCard {
            val done = content != null && s.totalChapters > 0 && s.currentChapterId == null
            val audioReady = ui.audio.isReadyFor(s.contentId)
            val canStart = content != null && s.disabledReason == null && audioReady
            val primary = PrimaryButton(
                label = if (done) "처음부터 다시" else "학습 시작",
                icon = if (done) ButtonIcon.REPLAY else ButtonIcon.PLAY_CIRCLE,
                action = if (canStart) PrimaryAction.Start else null,
            )
            val secondary = if (done) nextContent(catalog, content)?.let { SecondaryButton.NextContent(it.contentId, it.title) } else null
            val reason = s.disabledReason
            val status = when {
                ui.startRefusedMessage != null -> warn(ui.startRefusedMessage)
                reason != null -> warn("학습 불가: " + reason.blocked.entries.joinToString { (sig, block) -> "$sig - $block" })
                ui.audio is AudioStatus.Checking -> StatusLine(AUDIO_CHECKING, StatusIcon.HOURGLASS, StatusTone.WAIT)
                content != null && !audioReady -> StatusLine(AUDIO_MISSING, StatusIcon.WARNING, StatusTone.WARN, strong = true)
                done -> StatusLine(DONE, StatusIcon.CHECK_CIRCLE, StatusTone.DONE, strong = true)
                else -> StatusLine(OFF, StatusIcon.TOGGLE_OFF, StatusTone.OFF)
            }
            return LearningCard(header, status, null, primary, secondary, picker)
        }

        private fun on(
            s: LearningState,
            audio: AudioStatus,
            content: Content?,
            header: CardHeader?,
            picker: Picker?,
        ): LearningCard {
            val listen = ListenButton.of(s, audio)
            val playing = s.playback == PlaybackPhase.PREPARING || s.playback == PlaybackPhase.PLAYING
            val primary = PrimaryButton(
                label = listen.label,
                icon = if (playing) ButtonIcon.PAUSE else ButtonIcon.PLAY_ARROW,
                action = listen.command?.let { PrimaryAction.Listen(it) },
            )
            val chapterIndex = content?.let { c -> s.currentChapterId?.let { c.indexOf(it) } } ?: -1
            val now = if (playing && content != null && chapterIndex >= 0) {
                val ch = content.chapters[chapterIndex]
                NowPlaying(chapterIndex + 1, ch.word, ch.partOfSpeech, ch.meaningKo)
            } else null
            val status = when {
                playing -> StatusLine(
                    if (chapterIndex >= 0) "듣는 중 · ${chapterIndex + 1}번째 챕터" else "듣는 중",
                    StatusIcon.GRAPHIC_EQ, StatusTone.PLAYING, strong = true,
                )
                audio is AudioStatus.Checking -> StatusLine(AUDIO_CHECKING, StatusIcon.HOURGLASS, StatusTone.WAIT)
                !audio.isReadyFor(s.contentId) -> StatusLine(AUDIO_MISSING, StatusIcon.WARNING, StatusTone.WARN, strong = true)
                s.currentChapterId == null -> StatusLine(DONE, StatusIcon.CHECK_CIRCLE, StatusTone.DONE, strong = true)
                else -> when (s.gatePhase) {
                    GatePhase.ARMED -> StatusLine(ARMED, StatusIcon.VOLUME_UP, StatusTone.READY, strong = true, hint = ARMED_HINT)
                    GatePhase.USED -> StatusLine(USED, StatusIcon.SCHEDULE, StatusTone.WAIT)
                    GatePhase.INACTIVE, GatePhase.WAITING_DRIVE, GatePhase.DRIVE_CONFIRMING,
                    GatePhase.DETECTING, GatePhase.STOP_CONFIRMING, GatePhase.ARMED_CREEP ->
                        StatusLine(WAIT_STOP, StatusIcon.DIRECTIONS_CAR, StatusTone.WAIT)
                }
            }
            return LearningCard(header, status, now, primary, SecondaryButton.StopLearning, picker)
        }

        /** 카탈로그 순서에서 [content] 바로 다음 콘텐츠. 마지막이면 없음(처음으로 돌아가지 않음). */
        private fun nextContent(catalog: Catalog, content: Content?): Content? {
            val i = content?.let { catalog.contents.indexOf(it) } ?: return null
            return catalog.contents.getOrNull(i + 1)
        }

        private fun picker(catalog: Catalog, s: LearningState): Picker {
            val selected = s.contentId?.takeIf { catalog.content(it) != null }
            val rows = catalog.contents.map { c ->
                ContentRow(
                    contentId = c.contentId,
                    title = c.title,
                    chapterCount = c.chapters.size,
                    completed = s.completedByContent[c.contentId] ?: 0,
                    selected = c.contentId == selected,
                )
            }
            val others = catalog.contents.size - if (selected != null) 1 else 0
            return Picker(
                otherCount = others,
                enabled = !s.learningOn && others > 0,
                lockHint = if (s.learningOn) PICKER_LOCKED else null,
                rows = rows,
            )
        }
    }
}
