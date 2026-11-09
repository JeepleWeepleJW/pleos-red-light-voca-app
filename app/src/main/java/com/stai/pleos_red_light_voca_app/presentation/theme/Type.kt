package com.stai.pleos_red_light_voca_app.presentation.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * 글자 토큰(크기/줄 높이 sp, 굵기). 출처: `design/learning/LEARN-hifi-B.html` '구현용 토큰표'(글자 px = sp).
 * 글꼴은 시스템 기본 산세리프(사용자 승인).
 */
object VocaType {
    private fun style(size: Int, line: Int, weight: FontWeight) = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = line.sp,
        // 한 줄 높이를 토큰의 줄 높이 그대로 둔다(기본 Trim.Both는 첫·끝 줄 여백을 잘라 hi-fi보다 낮아진다).
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )

    /** 재생 중 현재 영단어 */
    val Word = style(72, 88, FontWeight.Bold).copy(letterSpacing = 0.5.sp)
    /** 카드 제목(콘텐츠 이름), 진도 */
    val CardTitle = style(28, 42, FontWeight.Bold)
    /** 재생 중 뜻 */
    val Meaning = style(28, 42, FontWeight.Normal)
    /** '지금' 칸 상태 한 줄(정차 확정·완료·미준비는 [StatusStrong]) */
    val Status = style(24, 36, FontWeight.Normal)
    val StatusStrong = style(24, 36, FontWeight.Bold)
    /** 주 버튼 */
    val Button = style(24, 36, FontWeight.Bold)
    /** 앱 제목 */
    val AppTitle = style(20, 30, FontWeight.Bold)
    /** 보조 버튼, 고르기 줄, 목록 행 제목 */
    val Secondary = style(20, 30, FontWeight.SemiBold)
    /** 재생 중 상단 "듣는 중 · n번째 챕터" */
    val StatusSmall = style(20, 30, FontWeight.Bold)
    /** 챕터 수, 잠금 안내, 목록 행 진도, 정차 확정 보조 줄 */
    val Caption = style(16, 24, FontWeight.Normal)
    /** 품사(기울임) */
    val CaptionItalic = Caption.copy(fontStyle = FontStyle.Italic)
    /** 목록 행 "선택됨" */
    val CaptionStrong = style(16, 24, FontWeight.Bold)
}

/** Material 기본 글자(디버그 카드·패널 등 토큰을 직접 쓰지 않는 곳). */
val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    )
)
