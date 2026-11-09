package com.stai.pleos_red_light_voca_app.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/** theme4 토큰을 Material 색 역할에 맞춘 것. 다크로 고정한다(시스템 설정·동적 색을 따르지 않음). */
private val VocaColorScheme = darkColorScheme(
    primary = VocaColors.Primary,
    onPrimary = VocaColors.OnPrimary,
    secondary = VocaColors.Primary,
    onSecondary = VocaColors.OnPrimary,
    tertiary = VocaColors.StatusReady,
    background = VocaColors.Bg,
    onBackground = VocaColors.Text,
    surface = VocaColors.Bg,
    onSurface = VocaColors.Text,
    surfaceVariant = VocaColors.Surface,
    onSurfaceVariant = VocaColors.TextMuted,
    surfaceContainer = VocaColors.Surface,
    surfaceContainerHighest = VocaColors.Surface,
    outline = VocaColors.Border,
    outlineVariant = VocaColors.DisabledBorder,
)

@Composable
fun PleosredlightvocaappTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VocaColorScheme,
        typography = Typography,
        content = content,
    )
}
