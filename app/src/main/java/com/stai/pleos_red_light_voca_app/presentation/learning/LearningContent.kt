package com.stai.pleos_red_light_voca_app.presentation.learning

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stai.pleos_red_light_voca_app.R
import com.stai.pleos_red_light_voca_app.core.entity.Catalog
import com.stai.pleos_red_light_voca_app.domain.gate.GatePhase
import com.stai.pleos_red_light_voca_app.domain.learning.LearningState
import com.stai.pleos_red_light_voca_app.presentation.theme.VocaColors
import com.stai.pleos_red_light_voca_app.presentation.theme.VocaType

/**
 * 학습 화면 본문(B안: 이어 하기 카드 + '다른 콘텐츠 고르기' 접힌 목록). 상태와 [onAction]만 받는다.
 * 무엇을 보일지는 [LearningCard.of]가 정하고, 여기서는 hi-fi(`design/learning/LEARN-hifi-B.html`) 크기·색 토큰으로 그리기만 한다(1px = 1dp, 글자 px = sp).
 * [showStatus]가 true일 때만 '상태' 카드를 그린다(debug 빌드 전용). [debugPanel]은 본문 끝에 그린다(debug 빌드의 디버그 패널 자리).
 */
@Composable
fun LearningContent(
    state: LearningUiState,
    onAction: (LearningAction) -> Unit,
    modifier: Modifier = Modifier,
    showStatus: Boolean = false,
    debugPanel: @Composable () -> Unit = {},
) {
    val card = LearningCard.of(state)
    // 펼침은 화면만의 상태. 학습 ON 등으로 고르기가 잠기면 접힌 것으로 본다.
    var expandedRequested by rememberSaveable { mutableStateOf(false) }
    val expanded = expandedRequested && card.picker?.enabled == true

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(VocaColors.Bg)
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 960.dp)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 48.dp, bottom = 48.dp),
        ) {
            Text("정차 중 영단어 학습", style = VocaType.AppTitle, color = VocaColors.TextMuted)
            Spacer(Modifier.height(24.dp))
            ResumeCard(card, onAction)
            card.picker?.let { picker ->
                Spacer(Modifier.height(24.dp))
                PickerRow(picker, expanded) { expandedRequested = !expandedRequested }
                if (expanded) {
                    Spacer(Modifier.height(16.dp))
                    ContentList(picker.rows) { id ->
                        onAction(LearningAction.SelectContent(id))
                        expandedRequested = false
                    }
                }
            }
            if (showStatus && state.catalog != null) {
                Spacer(Modifier.height(24.dp))
                StatusCard(state.learning, state.catalog)
            }
            Spacer(Modifier.height(24.dp))
            debugPanel()
        }
    }
}

private val CardShape = RoundedCornerShape(16.dp)
private val InnerShape = RoundedCornerShape(12.dp)

/** 이어 하기 카드: 높이 506 고정 = 32 + 42 + 16 + 8 + 24 + 240 + 24 + 88 + 32 */
@Composable
private fun ResumeCard(card: LearningCard, onAction: (LearningAction) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(506.dp)
            .clip(CardShape)
            .background(VocaColors.Bg)
            .border(2.dp, VocaColors.Border, CardShape)
            .padding(32.dp),
    ) {
        Box(Modifier.height(42.dp + 16.dp + 8.dp)) {
            card.header?.let { Header(it) }
        }
        Spacer(Modifier.height(24.dp))
        Stage(card)
        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth().height(88.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrimaryButtonView(card.primary, Modifier.weight(2f)) {
                when (card.primary.action) {
                    PrimaryAction.Start -> onAction(LearningAction.ClickStart)
                    is PrimaryAction.Listen -> onAction(LearningAction.ClickListen)
                    null -> Unit
                }
            }
            Box(Modifier.weight(1f)) {
                card.secondary?.let { b ->
                    SecondaryButtonView(b) {
                        when (b) {
                            SecondaryButton.StopLearning -> onAction(LearningAction.ClickStop)
                            is SecondaryButton.NextContent -> onAction(LearningAction.SelectContent(b.contentId))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(h: CardHeader) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().height(42.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    h.title, style = VocaType.CardTitle, color = VocaColors.Text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(12.dp))
                Text("(${h.chapterCount}챕터)", style = VocaType.Caption, color = VocaColors.TextMuted, maxLines = 1)
            }
            Text(h.progressText, style = VocaType.CardTitle, color = VocaColors.Text, maxLines = 1)
        }
        Spacer(Modifier.height(16.dp))
        ProgressBar(h.progressFraction)
    }
}

@Composable
private fun ProgressBar(fraction: Float) {
    val shape = RoundedCornerShape(4.dp)
    Box(Modifier.fillMaxWidth().height(8.dp).clip(shape).background(VocaColors.Track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(VocaColors.Accent))
    }
}

/** '지금' 칸: 높이 240 고정. 재생 중이면 왼쪽 띠 8 + 단어·뜻, 아니면 상태 한 줄(+보조 한 줄). */
@Composable
private fun Stage(card: LearningCard) {
    val now = card.nowPlaying
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .clip(InnerShape)
            .background(VocaColors.Surface),
    ) {
        if (now != null) Box(Modifier.width(8.dp).fillMaxHeight().background(VocaColors.Accent))
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = if (now != null) 24.dp else 32.dp, end = 32.dp, top = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            if (now != null) {
                StatusLineView(card.status, small = true)
                Spacer(Modifier.height(8.dp))
                Text(now.word, style = VocaType.Word, color = VocaColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(now.partOfSpeech, style = VocaType.CaptionItalic, color = VocaColors.TextMuted, maxLines = 1,
                        modifier = Modifier.alignByBaseline())
                    Text(now.meaningKo, style = VocaType.Meaning, color = VocaColors.Text, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.alignByBaseline())
                }
            } else {
                StatusLineView(card.status, small = false)
            }
        }
    }
}

@Composable
private fun StatusLineView(line: StatusLine, small: Boolean) {
    val muted = line.tone == StatusTone.OFF || line.tone == StatusTone.WAIT
    val textColor = if (muted) VocaColors.TextMuted else VocaColors.Text
    val style = when {
        small -> VocaType.StatusSmall
        line.strong -> VocaType.StatusStrong
        else -> VocaType.Status
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                painterResource(line.icon.res), contentDescription = null,
                tint = line.tone.color, modifier = Modifier.size(if (small) 24.dp else 32.dp),
            )
            Text(line.text, style = style, color = textColor, maxLines = line.maxLines, overflow = TextOverflow.Ellipsis)
        }
        line.hint?.let {
            Spacer(Modifier.height(8.dp))
            // 아이콘 32 + 사이 12 만큼 들여 상태 글자와 줄을 맞춘다.
            Text(it, style = VocaType.Caption, color = VocaColors.TextMuted, maxLines = 1, modifier = Modifier.padding(start = 44.dp))
        }
    }
}

@Composable
private fun PrimaryButtonView(b: PrimaryButton, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = b.enabled,
        modifier = modifier.height(88.dp),
        shape = InnerShape,
        border = BorderStroke(2.dp, if (b.enabled) VocaColors.Primary else VocaColors.DisabledBorder),
        colors = ButtonDefaults.buttonColors(
            containerColor = VocaColors.Primary,
            contentColor = VocaColors.OnPrimary,
            disabledContainerColor = VocaColors.DisabledBg,
            disabledContentColor = VocaColors.DisabledText,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        ButtonContent(b.icon, b.label, 32.dp, VocaType.Button)
    }
}

@Composable
private fun SecondaryButtonView(b: SecondaryButton, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(64.dp),
        shape = InnerShape,
        border = BorderStroke(2.dp, VocaColors.Primary),
        colors = ButtonDefaults.buttonColors(containerColor = VocaColors.Bg, contentColor = VocaColors.Primary),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        ButtonContent(b.icon, b.label, 24.dp, VocaType.Secondary)
    }
}

@Composable
private fun ButtonContent(icon: ButtonIcon, label: String, iconSize: Dp, style: TextStyle) {
    Icon(painterResource(icon.res), contentDescription = null, modifier = Modifier.size(iconSize))
    Spacer(Modifier.width(12.dp))
    Text(label, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** '다른 콘텐츠 고르기' 줄: 높이 64. 학습 ON이면 비활성 + 잠금 안내. */
@Composable
private fun PickerRow(picker: Picker, expanded: Boolean, onToggle: () -> Unit) {
    val enabled = picker.enabled
    val border = when {
        !enabled -> VocaColors.DisabledBorder
        expanded -> VocaColors.Primary
        else -> VocaColors.BorderSubtle
    }
    val fg = if (enabled) VocaColors.Text else VocaColors.DisabledText
    val side = if (enabled) VocaColors.TextMuted else VocaColors.DisabledText
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(InnerShape)
            .background(if (enabled) VocaColors.Surface else VocaColors.DisabledBg)
            .border(2.dp, border, InnerShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = if (expanded) "펼침" else "접힘" }
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(picker.label, style = VocaType.Secondary, color = fg, maxLines = 1, modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val icon = when {
                picker.lockHint != null -> R.drawable.ic_lock
                expanded -> R.drawable.ic_expand_less
                else -> R.drawable.ic_expand_more
            }
            Icon(painterResource(icon), contentDescription = null, tint = side, modifier = Modifier.size(24.dp))
            picker.lockHint?.let { Text(it, style = VocaType.Caption, color = side, maxLines = 1) }
        }
    }
}

/** 펼친 목록: 행 높이 72, 행 사이 16, 행 전체 누름. */
@Composable
private fun ContentList(rows: List<ContentRow>, onSelect: (String) -> Unit) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        rows.forEach { r ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .clip(InnerShape)
                    .background(VocaColors.Surface)
                    .border(2.dp, if (r.selected) VocaColors.Primary else VocaColors.BorderSubtle, InnerShape)
                    .selectable(selected = r.selected, role = Role.RadioButton) { onSelect(r.contentId) }
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(
                    painterResource(if (r.selected) R.drawable.ic_radio_button_checked else R.drawable.ic_radio_button_unchecked),
                    contentDescription = null,
                    tint = if (r.selected) VocaColors.Primary else VocaColors.TextMuted,
                    modifier = Modifier.size(28.dp),
                )
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.title, style = VocaType.Secondary, color = VocaColors.Text, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    Text(r.chapterText, style = VocaType.Caption, color = VocaColors.TextMuted, maxLines = 1)
                }
                Text(if (r.done) LearningCard.DONE else r.progressText, style = VocaType.Caption, color = VocaColors.TextMuted, maxLines = 1)
                if (r.selected) Text("선택됨", style = VocaType.CaptionStrong, color = VocaColors.Text, maxLines = 1)
            }
        }
    }
}

@get:DrawableRes
private val StatusIcon.res: Int
    get() = when (this) {
        StatusIcon.TOGGLE_OFF -> R.drawable.ic_toggle_off
        StatusIcon.DIRECTIONS_CAR -> R.drawable.ic_directions_car
        StatusIcon.VOLUME_UP -> R.drawable.ic_volume_up
        StatusIcon.GRAPHIC_EQ -> R.drawable.ic_graphic_eq
        StatusIcon.SCHEDULE -> R.drawable.ic_schedule
        StatusIcon.CHECK_CIRCLE -> R.drawable.ic_check_circle
        StatusIcon.WARNING -> R.drawable.ic_warning
        StatusIcon.HOURGLASS -> R.drawable.ic_hourglass_empty
    }

private val StatusTone.color: Color
    get() = when (this) {
        StatusTone.OFF -> VocaColors.StatusOff
        StatusTone.WAIT -> VocaColors.StatusWait
        StatusTone.READY -> VocaColors.StatusReady
        StatusTone.PLAYING -> VocaColors.StatusPlaying
        StatusTone.DONE -> VocaColors.StatusDone
        StatusTone.WARN -> VocaColors.StatusWarn
    }

@get:DrawableRes
private val ButtonIcon.res: Int
    get() = when (this) {
        ButtonIcon.PLAY_CIRCLE -> R.drawable.ic_play_circle
        ButtonIcon.PLAY_ARROW -> R.drawable.ic_play_arrow
        ButtonIcon.PAUSE -> R.drawable.ic_pause
        ButtonIcon.REPLAY -> R.drawable.ic_replay
        ButtonIcon.STOP_CIRCLE -> R.drawable.ic_stop_circle
        ButtonIcon.SKIP_NEXT -> R.drawable.ic_skip_next
    }

// ---- debug 빌드 전용 '상태' 카드(시안에 없음, 세부 판정 확인용) ----

private fun gateText(p: GatePhase) = when (p) {
    GatePhase.INACTIVE -> "학습 꺼짐"
    GatePhase.WAITING_DRIVE -> "주행 대기"
    GatePhase.DRIVE_CONFIRMING -> "주행 확인 중"
    GatePhase.DETECTING -> "정차 감지 중"
    GatePhase.STOP_CONFIRMING -> "정차 확인 중"
    GatePhase.ARMED -> "재생 가능 — 듣기 버튼이나 차량 Play 키를 누르세요"
    GatePhase.ARMED_CREEP -> "서행 중(재생 불가)"
    GatePhase.USED -> "이번 정차 재생 사용함"
}

@Composable
private fun StatusCard(s: LearningState, catalog: Catalog) {
    val content = s.contentId?.let { catalog.content(it) }
    val word = s.currentChapterId?.let { id -> content?.chapters?.firstOrNull { it.chapterId == id } }
    val v = s.vehicle
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("상태", style = MaterialTheme.typography.titleMedium)
            Text("학습: ${if (s.learningOn) "ON" else "OFF"}")
            Text("판정: ${s.gatePhase.name} (${gateText(s.gatePhase)})")
            Text("재생: ${s.playback.name}")
            Text(
                "속도: " + (v.speedKmh?.let { "%.1f km/h".format(it) } ?: "-") +
                    (v.speedFailure?.let { " ($it)" } ?: "")
            )
            Text("기어: ${v.gear ?: "-"}" + (v.gearFailure?.let { " ($it)" } ?: ""))
            val chapterText = when {
                word != null -> "${word.word} — ${word.meaningKo}"
                // 콘텐츠를 골랐고 다음 챕터가 없을 때만 완료로 표시(미선택·로딩 전은 "-")
                content != null && s.currentChapterId == null && s.totalChapters > 0 -> "모두 완료"
                else -> "-"
            }
            Text("현재 챕터: $chapterText")
            Text("진도: ${s.completedChapters}/${s.totalChapters}")
            Text("마지막 재생 종료: ${s.lastPlaybackEnd?.name ?: "-"}")
            if (s.sessionCompleted) Text("콘텐츠 완료")
        }
    }
}
