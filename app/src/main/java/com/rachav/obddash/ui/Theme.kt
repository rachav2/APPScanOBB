package com.rachav.obddash.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Ajustes sencillos de la app. Cambia el texto si quieres otro nombre en la franja inferior. */
object AppConfig {
    const val CAR_NAME = "CIVIC"
}

object Palette {
    val Bg = Color(0xFF030812)
    val Surface = Color(0xFF0A1428)
    val Surface2 = Color(0xFF142340)
    val Track = Color(0xFF14233F)
    val Cyan = Color(0xFF3FB6FF)
    val Blue = Color(0xFF2F6BFF)
    val BlueBright = Color(0xFF2F8BFF)
    val Violet = Color(0xFF7C4DFF)
    val Red = Color(0xFFFF2D3F)
    val Amber = Color(0xFFFFB300)
    val Green = Color(0xFF22E06B)
    val Text = Color(0xFFF2F6FF)
    val TextDim = Color(0xFF8CA0C8)
    val CardTop = Color(0xFF0E1F3D)
    val CardBottom = Color(0xFF060E1D)
}

@Composable
fun ObdTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.BlueBright,
            onPrimary = Color.White,
            secondary = Palette.Blue,
            secondaryContainer = Color(0xFF15305E),
            onSecondaryContainer = Palette.Text,
            background = Palette.Bg,
            onBackground = Palette.Text,
            surface = Palette.Surface,
            onSurface = Palette.Text,
            surfaceVariant = Palette.Surface2,
            onSurfaceVariant = Palette.TextDim,
            error = Palette.Red,
        ),
        content = content,
    )
}
