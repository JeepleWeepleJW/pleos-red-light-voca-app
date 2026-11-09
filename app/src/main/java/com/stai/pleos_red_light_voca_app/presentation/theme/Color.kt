package com.stai.pleos_red_light_voca_app.presentation.theme

import androidx.compose.ui.graphics.Color

/**
 * 색 토큰 theme4(Pleos 다크 + 파랑, 사용자 확정 2026-11-09).
 * 출처: `design/learning/LEARN-hifi-B.html` 머리 주석 '구현용 토큰표'.
 * 팔레트 https://colorhunt.co/palette/1b262c0f4c753282b8bbe1fa + Pleos 색 토큰(#131417·#FFFFFF·#0082FF).
 */
object VocaColors {
    /** 앱 화면 바탕, 카드 바탕, 보조 버튼 바탕 */
    val Bg = Color(0xFF131417)
    /** 카드 안 '지금' 칸, 고르기 줄·목록 행 바탕 */
    val Surface = Color(0xFF1B262C)
    /** 주 글자(제목·진도·단어·뜻·상태) */
    val Text = Color(0xFFFFFFFF)
    /** 보조 글자(앱 제목·챕터 수·품사·잠금 안내), 학습 꺼짐·대기 상태 글자 */
    val TextMuted = Color(0xFFBFC2C4)
    /** 주 버튼 채움, 보조 버튼 테두리·글자, 선택된 목록 행 표시 */
    val Primary = Color(0xFF0082FF)
    /** 주 버튼 위 글자·아이콘 */
    val OnPrimary = Color(0xFF131417)
    /** 카드 테두리 */
    val Border = Color(0xFF767778)
    /** 고르기 줄·목록 행 테두리 */
    val BorderSubtle = Color(0xFF767D80)
    /** 비활성 버튼·줄 바탕 */
    val DisabledBg = Color(0xFF242427)
    /** 비활성 버튼·줄 글자 */
    val DisabledText = Color(0xFF767778)
    /** 비활성 버튼·줄 테두리 */
    val DisabledBorder = Color(0xFF343537)
    /** 진도 막대 채움, 재생 중 '지금' 칸 왼쪽 띠 */
    val Accent = Color(0xFF0082FF)
    /** 진도 막대 바탕 */
    val Track = Color(0xFF393A3C)
    /** 상태 아이콘: 학습 꺼짐 */
    val StatusOff = Color(0xFFBFC2C4)
    /** 상태 아이콘: 주행 대기·이번 정차 사용함·음원 확인 중·불러오는 중 */
    val StatusWait = Color(0xFFBFC2C4)
    /** 상태 아이콘: 정차 확정 */
    val StatusReady = Color(0xFFBBE1FA)
    /** 상태 아이콘: 듣는 중 */
    val StatusPlaying = Color(0xFFBBE1FA)
    /** 상태 아이콘: 콘텐츠 완료 */
    val StatusDone = Color(0xFFFFFFFF)
    /** 상태 아이콘: 음원 미준비·오류(빨강 없이 아이콘 모양+문구로 구분) */
    val StatusWarn = Color(0xFFFFFFFF)
}
