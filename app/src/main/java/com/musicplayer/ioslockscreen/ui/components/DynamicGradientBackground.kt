package com.musicplayer.ioslockscreen.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GradientThemeColors(
    val topColor: Color = Color(0xFF1E1E24),      // Tono grafito neutro iOS por defecto
    val middleColor: Color = Color(0xFF131317),
    val bottomColor: Color = Color(0xFF09090B)
)

suspend fun extractGradientColorsFromBitmap(bitmap: Bitmap?): GradientThemeColors = withContext(Dispatchers.Default) {
    if (bitmap == null) {
        return@withContext GradientThemeColors()
    }

    try {
        val palette = Palette.from(bitmap).generate()

        val dominant = palette.getDominantColor(0xFF1E1E24.toInt())
        val vibrant = palette.getVibrantColor(dominant)
        val darkVibrant = palette.getDarkVibrantColor(0xFF131317.toInt())
        val darkMuted = palette.getDarkMutedColor(0xFF09090B.toInt())
        val lightMuted = palette.getLightMutedColor(dominant)

        // En iOS 16, el gradiente superior adopta la tonalidad viva de la portada con suavidad,
        // fundiéndose hacia tonos profundos y oscuros abajo para garantizar máximo contraste y elegancia
        val top = Color(if (vibrant != dominant) vibrant else dominant).copy(alpha = 0.85f)
        val middle = Color(darkVibrant).copy(alpha = 0.92f)
        val bottom = Color(0xFF08080A)

        GradientThemeColors(
            topColor = top,
            middleColor = middle,
            bottomColor = bottom
        )
    } catch (e: Exception) {
        GradientThemeColors()
    }
}

@Composable
fun DynamicGradientBackground(
    bitmap: Bitmap?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    var themeColors by remember { mutableStateOf(GradientThemeColors()) }

    LaunchedEffect(bitmap) {
        themeColors = extractGradientColorsFromBitmap(bitmap)
    }

    // Animación suave de transición de colores cuando cambia la canción
    val animatedTop by animateColorAsState(
        targetValue = themeColors.topColor,
        animationSpec = tween(durationMillis = 800),
        label = "TopGradient"
    )
    val animatedMiddle by animateColorAsState(
        targetValue = themeColors.middleColor,
        animationSpec = tween(durationMillis = 800),
        label = "MiddleGradient"
    )
    val animatedBottom by animateColorAsState(
        targetValue = themeColors.bottomColor,
        animationSpec = tween(durationMillis = 800),
        label = "BottomGradient"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        animatedTop,
                        animatedMiddle,
                        animatedBottom
                    )
                )
            ),
        content = content
    )
}
