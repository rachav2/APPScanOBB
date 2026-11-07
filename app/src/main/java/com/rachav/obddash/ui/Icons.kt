package com.rachav.obddash.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

enum class Ico {
    Menu, Bluetooth, Car, Thermo, Fuel, Drop, Gauge, Dial, Battery, Wind,
    Oil, Exhaust, Road, Pin, Clock, Chip, Warning, Check, Grid,
}

/** Iconos de línea dibujados a mano (sin dependencias extra). Se escalan al tamaño que les des. */
@Composable
fun ObdIcon(kind: Ico, modifier: Modifier = Modifier.size(24.dp), color: Color = Palette.Cyan) {
    Canvas(modifier) {
        val s = size.minDimension
        val sw = s * 0.075f
        val st = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color = color, start = p(x1, y1), end = p(x2, y2), strokeWidth = sw, cap = StrokeCap.Round)
        fun poly(closed: Boolean, vararg pts: Pair<Float, Float>) {
            val path = Path()
            pts.forEachIndexed { i, (x, y) ->
                if (i == 0) path.moveTo(x * s, y * s) else path.lineTo(x * s, y * s)
            }
            if (closed) path.close()
            drawPath(path = path, color = color, style = st)
        }

        when (kind) {
            Ico.Menu -> {
                line(0.12f, 0.28f, 0.88f, 0.28f)
                line(0.12f, 0.50f, 0.88f, 0.50f)
                line(0.12f, 0.72f, 0.88f, 0.72f)
            }
            Ico.Bluetooth -> poly(false, 0.3f to 0.3f, 0.7f to 0.7f, 0.5f to 0.9f, 0.5f to 0.1f, 0.7f to 0.3f, 0.3f to 0.7f)
            Ico.Car -> {
                poly(true, 0.08f to 0.62f, 0.08f to 0.46f, 0.20f to 0.44f, 0.32f to 0.26f, 0.68f to 0.26f, 0.80f to 0.44f, 0.92f to 0.46f, 0.92f to 0.62f)
                drawCircle(color = color, radius = 0.09f * s, center = p(0.28f, 0.66f))
                drawCircle(color = color, radius = 0.09f * s, center = p(0.72f, 0.66f))
            }
            Ico.Thermo -> {
                drawRoundRect(
                    color = color, topLeft = p(0.40f, 0.08f), size = Size(0.20f * s, 0.56f * s),
                    cornerRadius = CornerRadius(0.10f * s, 0.10f * s), style = st,
                )
                drawCircle(color = color, radius = 0.17f * s, center = p(0.5f, 0.76f))
                line(0.5f, 0.30f, 0.5f, 0.70f)
                line(0.72f, 0.18f, 0.84f, 0.18f)
                line(0.72f, 0.34f, 0.84f, 0.34f)
                line(0.72f, 0.50f, 0.84f, 0.50f)
            }
            Ico.Fuel -> {
                drawRoundRect(
                    color = color, topLeft = p(0.14f, 0.12f), size = Size(0.46f * s, 0.76f * s),
                    cornerRadius = CornerRadius(0.06f * s, 0.06f * s), style = st,
                )
                drawRoundRect(
                    color = color, topLeft = p(0.23f, 0.21f), size = Size(0.28f * s, 0.2f * s),
                    cornerRadius = CornerRadius(0.03f * s, 0.03f * s), style = st,
                )
                poly(false, 0.60f to 0.34f, 0.74f to 0.34f, 0.84f to 0.46f, 0.84f to 0.72f)
                drawCircle(color = color, radius = 0.06f * s, center = p(0.84f, 0.77f))
            }
            Ico.Drop -> {
                val path = Path().apply {
                    moveTo(0.5f * s, 0.1f * s)
                    cubicTo(0.5f * s, 0.1f * s, 0.2f * s, 0.45f * s, 0.2f * s, 0.62f * s)
                    cubicTo(0.2f * s, 0.8f * s, 0.34f * s, 0.92f * s, 0.5f * s, 0.92f * s)
                    cubicTo(0.66f * s, 0.92f * s, 0.8f * s, 0.8f * s, 0.8f * s, 0.62f * s)
                    cubicTo(0.8f * s, 0.45f * s, 0.5f * s, 0.1f * s, 0.5f * s, 0.1f * s)
                    close()
                }
                drawPath(path = path, color = color, style = st)
            }
            Ico.Gauge -> {
                drawArc(
                    color = color, startAngle = 150f, sweepAngle = 240f, useCenter = false,
                    topLeft = p(0.1f, 0.1f), size = Size(0.8f * s, 0.8f * s), style = st,
                )
                line(0.5f, 0.58f, 0.68f, 0.34f)
                drawCircle(color = color, radius = 0.06f * s, center = p(0.5f, 0.58f))
            }
            Ico.Dial -> {
                drawCircle(color = color, radius = 0.40f * s, center = p(0.5f, 0.5f), style = st)
                line(0.5f, 0.5f, 0.66f, 0.30f)
                drawCircle(color = color, radius = 0.06f * s, center = p(0.5f, 0.5f))
                line(0.26f, 0.64f, 0.32f, 0.60f)
                line(0.74f, 0.64f, 0.68f, 0.60f)
            }
            Ico.Battery -> {
                drawRoundRect(
                    color = color, topLeft = p(0.08f, 0.30f), size = Size(0.84f * s, 0.52f * s),
                    cornerRadius = CornerRadius(0.06f * s, 0.06f * s), style = st,
                )
                line(0.26f, 0.20f, 0.38f, 0.20f)
                line(0.62f, 0.20f, 0.74f, 0.20f)
                line(0.22f, 0.56f, 0.40f, 0.56f)
                line(0.31f, 0.47f, 0.31f, 0.65f)
                line(0.60f, 0.56f, 0.78f, 0.56f)
            }
            Ico.Wind -> {
                val p1 = Path().apply {
                    moveTo(0.10f * s, 0.34f * s); lineTo(0.58f * s, 0.34f * s)
                    quadraticTo(0.84f * s, 0.34f * s, 0.84f * s, 0.20f * s)
                    quadraticTo(0.84f * s, 0.10f * s, 0.72f * s, 0.10f * s)
                }
                val p2 = Path().apply {
                    moveTo(0.10f * s, 0.54f * s); lineTo(0.84f * s, 0.54f * s)
                    quadraticTo(0.95f * s, 0.54f * s, 0.95f * s, 0.66f * s)
                    quadraticTo(0.95f * s, 0.76f * s, 0.84f * s, 0.76f * s)
                }
                val p3 = Path().apply {
                    moveTo(0.10f * s, 0.74f * s); lineTo(0.50f * s, 0.74f * s)
                    quadraticTo(0.70f * s, 0.74f * s, 0.70f * s, 0.86f * s)
                }
                drawPath(path = p1, color = color, style = st)
                drawPath(path = p2, color = color, style = st)
                drawPath(path = p3, color = color, style = st)
            }
            Ico.Oil -> {
                poly(true, 0.14f to 0.42f, 0.62f to 0.42f, 0.62f to 0.78f, 0.14f to 0.78f)
                poly(false, 0.62f to 0.50f, 0.90f to 0.30f)
                line(0.14f, 0.50f, 0.06f, 0.36f)
                drawCircle(color = color, radius = 0.05f * s, center = p(0.86f, 0.64f))
            }
            Ico.Exhaust -> {
                drawRoundRect(
                    color = color, topLeft = p(0.08f, 0.32f), size = Size(0.84f * s, 0.36f * s),
                    cornerRadius = CornerRadius(0.10f * s, 0.10f * s), style = st,
                )
                line(0.36f, 0.32f, 0.36f, 0.68f)
                line(0.50f, 0.32f, 0.50f, 0.68f)
                line(0.64f, 0.32f, 0.64f, 0.68f)
            }
            Ico.Road -> {
                line(0.10f, 0.92f, 0.40f, 0.10f)
                line(0.90f, 0.92f, 0.60f, 0.10f)
                line(0.50f, 0.88f, 0.50f, 0.74f)
                line(0.50f, 0.62f, 0.50f, 0.48f)
                line(0.50f, 0.36f, 0.50f, 0.22f)
            }
            Ico.Pin -> {
                poly(false, 0.28f to 0.48f, 0.5f to 0.92f, 0.72f to 0.48f)
                drawCircle(color = color, radius = 0.22f * s, center = p(0.5f, 0.40f), style = st)
                drawCircle(color = color, radius = 0.07f * s, center = p(0.5f, 0.40f))
            }
            Ico.Clock -> {
                drawCircle(color = color, radius = 0.40f * s, center = p(0.5f, 0.5f), style = st)
                line(0.5f, 0.5f, 0.5f, 0.26f)
                line(0.5f, 0.5f, 0.68f, 0.58f)
            }
            Ico.Chip -> {
                drawRoundRect(
                    color = color, topLeft = p(0.26f, 0.26f), size = Size(0.48f * s, 0.48f * s),
                    cornerRadius = CornerRadius(0.05f * s, 0.05f * s), style = st,
                )
                drawRoundRect(
                    color = color, topLeft = p(0.40f, 0.40f), size = Size(0.20f * s, 0.20f * s),
                    cornerRadius = CornerRadius(0.02f * s, 0.02f * s),
                )
                listOf(0.38f, 0.5f, 0.62f).forEach { t ->
                    line(t, 0.12f, t, 0.26f)
                    line(t, 0.74f, t, 0.88f)
                    line(0.12f, t, 0.26f, t)
                    line(0.74f, t, 0.88f, t)
                }
            }
            Ico.Warning -> {
                poly(true, 0.5f to 0.10f, 0.93f to 0.86f, 0.07f to 0.86f)
                line(0.5f, 0.40f, 0.5f, 0.62f)
                drawCircle(color = color, radius = 0.045f * s, center = p(0.5f, 0.73f))
            }
            Ico.Check -> {
                drawCircle(color = color, radius = 0.44f * s, center = p(0.5f, 0.5f), style = st)
                poly(false, 0.28f to 0.52f, 0.45f to 0.69f, 0.74f to 0.34f)
            }
            Ico.Grid -> {
                listOf(0.2f, 0.5f, 0.8f).forEach { x ->
                    listOf(0.2f, 0.5f, 0.8f).forEach { y ->
                        drawCircle(color = color, radius = 0.075f * s, center = p(x, y))
                    }
                }
            }
        }
    }
}
