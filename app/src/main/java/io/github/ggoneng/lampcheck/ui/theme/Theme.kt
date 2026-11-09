// -----------------------------------------------------------------------------------
// 파일명       : Theme.kt
// 설명         : 램프체크 Material3 테마 (어두운 색, 차량 화면용 큰 글자)
// 작성자       : 이민하
// 작성일       : 2026-11-09
// -----------------------------------------------------------------------------------
// >> 주요 기능
// - 어두운 색 구성과 큰 글자 크기를 Material3 테마로 묶음
// -----------------------------------------------------------------------------------

package io.github.ggoneng.lampcheck.ui.theme

// ----------------------------------------------------------
// Modules
// ----------------------------------------------------------

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ----------------------------------------------------------
// Internal Variables (do not call externally)
// ----------------------------------------------------------

// 어두운 색 구성
private val LampColorScheme: ColorScheme = darkColorScheme(
    primary = AccentColor,
    onPrimary = Color.Black,
    background = BackgroundColor,
    onBackground = TextPrimaryColor,
    surface = PanelColor,
    onSurface = TextPrimaryColor,
    surfaceVariant = PanelHighColor,
    onSurfaceVariant = TextSecondaryColor
)

// 운전 중에도 읽기 쉬운 큰 글자
private val LampTypography: Typography = Typography(
    headlineSmall = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp),
    titleLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 22.sp),
    bodyMedium = TextStyle(fontSize = 20.sp),
    labelLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 18.sp)
)

// ----------------------------------------------------------
// External Variables (can be called from outside)
// ----------------------------------------------------------

// ----------------------------------------------------------
// Internal Classes (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Classes (can be called from outside)
// ----------------------------------------------------------

// ----------------------------------------------------------
// Internal Functions (do not call externally)
// ----------------------------------------------------------

// ----------------------------------------------------------
// External Functions (can be called from outside)
// ----------------------------------------------------------

// 램프체크 테마
@Composable
fun LampCheckTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LampColorScheme, typography = LampTypography, content = content)
}
