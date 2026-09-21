package com.photovault.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 配色跟网页控制台保持一致（同一个蓝），两边看着才像一套东西。
 * 深浅色都手写全，别只给主色 —— 缺的字段 Material3 会拿默认紫色兜底，很出戏。
 */
private val Light = lightColorScheme(
    primary = Color(0xFF2F6FED),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF001B4D),
    secondary = Color(0xFF5B6B84),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E9F2),
    onSecondaryContainer = Color(0xFF182232),
    tertiary = Color(0xFF1B9E75),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD3F3E7),
    onTertiaryContainer = Color(0xFF00301F),
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF191C21),
    surface = Color.White,
    onSurface = Color(0xFF191C21),
    surfaceVariant = Color(0xFFEFF1F5),
    onSurfaceVariant = Color(0xFF5D646F),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBFCFD),
    surfaceContainer = Color(0xFFF3F5F8),
    outline = Color(0xFFC7CDD6),
    outlineVariant = Color(0xFFE6E9EF),
    error = Color(0xFFD8443C),
    onError = Color.White,
    errorContainer = Color(0xFFFDE9E7),
    onErrorContainer = Color(0xFF4A120C),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA9C8FF),
    onPrimary = Color(0xFF002A6B),
    primaryContainer = Color(0xFF1B3A6B),
    onPrimaryContainer = Color(0xFFDCE8FF),
    secondary = Color(0xFFBFC7D5),
    onSecondary = Color(0xFF1B2431),
    secondaryContainer = Color(0xFF2A323D),
    onSecondaryContainer = Color(0xFFDDE3EC),
    tertiary = Color(0xFF5DCAA5),
    onTertiary = Color(0xFF00382A),
    tertiaryContainer = Color(0xFF10503C),
    onTertiaryContainer = Color(0xFFD3F3E7),
    background = Color(0xFF0F1216),
    onBackground = Color(0xFFE4E7EB),
    surface = Color(0xFF171B21),
    onSurface = Color(0xFFE4E7EB),
    surfaceVariant = Color(0xFF232932),
    onSurfaceVariant = Color(0xFFA6AEB9),
    surfaceContainerLowest = Color(0xFF0B0E12),
    surfaceContainerLow = Color(0xFF14181E),
    surfaceContainer = Color(0xFF1B2027),
    outline = Color(0xFF444C58),
    outlineVariant = Color(0xFF2A313B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF5C150F),
    errorContainer = Color(0xFF7A241C),
    onErrorContainer = Color(0xFFFFDAD6),
)

/** 圆角统一放大一点，照片类应用用方块感太重 */
private val AppShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 17.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 15.sp),
)

@Composable
fun PhotoVaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
