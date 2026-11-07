package com.rachav.obddash.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// ============================================================================ FORMATO

fun fmt(v: Int?): String = v?.toString() ?: "--"

fun fmtD(v: Double?, decimals: Int = 1): String =
    if (v == null) "--" else String.format(Locale.US, "%.${decimals}f", v)

fun fmtF(v: Float?, decimals: Int = 1): String =
    if (v == null) "--" else String.format(Locale.US, "%.${decimals}f", v)

/** Progreso 0..1 de un valor dentro de un rango (null si no hay dato). */
fun prog(v: Number?, min: Float, max: Float): Float? =
    v?.let { ((it.toFloat() - min) / (max - min)).coerceIn(0f, 1f) }

// ============================================================================ DIAL DE RPM

/**
 * Tacómetro semicircular: arco azul con halo, marcas finas, números 0–8 hacia adentro,
 * zona roja y las RPM grandes en el centro.
 */
@Composable
fun RpmDial(
    rpm: Int?,
    badge: String,
    modifier: Modifier = Modifier,
    maxRpm: Int = 8000,
    redline: Int = 6500,
) {
    val animRpm by animateFloatAsState(
        targetValue = (rpm ?: 0).toFloat(),
        animationSpec = tween(durationMillis = 220, easing = LinearEasing),
        label = "rpm",
    )
    val textMeasurer = rememberTextMeasurer()

    BoxWithConstraints(modifier = modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        val w = maxWidth.value

        Canvas(modifier = Modifier.fillMaxSize()) {
            val s = size.minDimension
            val c = Offset(size.width / 2f, size.height / 2f)
            val startA = 155f
            val sweep = 230f
            val stroke = s * 0.04f
            val r = s * 0.44f
            val topLeft = Offset(c.x - r, c.y - r)
            val arcSize = Size(2 * r, 2 * r)
            val frac = (animRpm / maxRpm).coerceIn(0f, 1f)
            val redFrac = redline.toFloat() / maxRpm

            // Resplandor azul de fondo
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Palette.Blue.copy(alpha = 0.20f), Color.Transparent),
                    center = c,
                    radius = r * 1.05f,
                ),
                radius = r * 1.05f,
                center = c,
            )

            // Pista y zona roja
            drawArc(
                color = Palette.Track, startAngle = startA, sweepAngle = sweep, useCenter = false,
                topLeft = topLeft, size = arcSize, style = Stroke(width = stroke, cap = StrokeCap.Butt),
            )
            drawArc(
                color = Palette.Red.copy(alpha = 0.9f),
                startAngle = startA + sweep * redFrac, sweepAngle = sweep * (1f - redFrac), useCenter = false,
                topLeft = topLeft, size = arcSize, style = Stroke(width = stroke * 1.15f, cap = StrokeCap.Butt),
            )

            // Arco activo con halo (el degradado se rota para empezar donde empieza el arco)
            if (frac > 0.003f) {
                rotate(degrees = startA, pivot = c) {
                    val brush = Brush.sweepGradient(
                        0f to Color(0xFF1B5CC8),
                        0.40f to Color(0xFF2F8BFF),
                        0.50f to Color(0xFF7FD4FF),
                        0.519f to Palette.Red,
                        0.64f to Palette.Red,
                        1f to Palette.Red,
                        center = c,
                    )
                    drawArc(
                        brush = brush, startAngle = 0f, sweepAngle = sweep * frac, useCenter = false,
                        topLeft = topLeft, size = arcSize, alpha = 0.25f,
                        style = Stroke(width = stroke * 2.4f, cap = StrokeCap.Butt),
                    )
                    drawArc(
                        brush = brush, startAngle = 0f, sweepAngle = sweep * frac, useCenter = false,
                        topLeft = topLeft, size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Butt),
                    )
                }
            }

            // Marcas finas cada 250 rpm y números cada 1000
            val steps = maxRpm / 250
            for (i in 0..steps) {
                val major = i % 4 == 0
                val ang = Math.toRadians((startA + sweep * i / steps).toDouble())
                val ca = cos(ang).toFloat()
                val sa = sin(ang).toFloat()
                val rOut = r - stroke * 1.3f
                val rIn = rOut - s * (if (major) 0.045f else 0.022f)
                val tickColor = when {
                    i * 250 >= redline -> Palette.Red
                    major -> Color.White
                    else -> Palette.TextDim
                }
                drawLine(
                    color = tickColor,
                    start = c + Offset(ca * rIn, sa * rIn),
                    end = c + Offset(ca * rOut, sa * rOut),
                    strokeWidth = if (major) s * 0.006f else s * 0.003f,
                )
                if (major) {
                    val layout = textMeasurer.measure(
                        text = (i / 4).toString(),
                        style = TextStyle(
                            color = Color.White,
                            fontSize = (s * 0.065f).toSp(),
                            fontWeight = FontWeight.Bold,
                            fontStyle = FontStyle.Italic,
                        ),
                    )
                    val rl = rIn - s * 0.06f
                    drawText(
                        textLayoutResult = layout,
                        topLeft = Offset(
                            c.x + ca * rl - layout.size.width / 2f,
                            c.y + sa * rl - layout.size.height / 2f,
                        ),
                    )
                }
            }

            // Marca blanca en la punta del arco
            if (frac > 0.003f) {
                val ang = Math.toRadians((startA + sweep * frac).toDouble())
                val ca = cos(ang).toFloat()
                val sa = sin(ang).toFloat()
                drawLine(
                    color = Color.White,
                    start = c + Offset(ca * (r - stroke * 1.7f), sa * (r - stroke * 1.7f)),
                    end = c + Offset(ca * (r + stroke * 0.9f), sa * (r + stroke * 0.9f)),
                    strokeWidth = s * 0.008f,
                )
            }
        }

        Column(
            modifier = Modifier.offset(y = (-w * 0.03f).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = rpm?.toString() ?: "--",
                color = Palette.Text,
                fontSize = (w * 0.20f).sp,
                fontWeight = FontWeight.ExtraBold,
                fontStyle = FontStyle.Italic,
            )
            Text(
                text = "RPM",
                color = Palette.BlueBright,
                fontSize = (w * 0.065f).sp,
                fontWeight = FontWeight.Bold,
                fontStyle = FontStyle.Italic,
            )
        }

        // Chip inferior (estado de la conexión)
        val chipShape = RoundedCornerShape((w * 0.025f).dp)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = (w * 0.13f).dp)
                .clip(chipShape)
                .background(Palette.Surface2)
                .border(1.dp, Palette.Blue.copy(alpha = 0.6f), chipShape)
                .padding(horizontal = (w * 0.045f).dp, vertical = (w * 0.012f).dp),
        ) {
            Text(
                text = badge,
                color = Palette.Text,
                fontSize = (w * 0.04f).sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
        }
    }
}

// ============================================================================ ARCO DE VELOCIDAD

/** Arco abierto hacia abajo con la velocidad grande al centro (pantalla "Velocidad y Viaje"). */
@Composable
fun SpeedArc(speed: Int?, modifier: Modifier = Modifier, maxSpeed: Int = 220) {
    val anim by animateFloatAsState(
        targetValue = (speed ?: 0).toFloat(),
        animationSpec = tween(durationMillis = 250, easing = LinearEasing),
        label = "speed",
    )
    BoxWithConstraints(modifier = modifier.aspectRatio(1.5f), contentAlignment = Alignment.Center) {
        val h = maxWidth.value / 1.5f

        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = min(size.width * 0.42f, size.height * 0.60f)
            val c = Offset(size.width / 2f, size.height * 0.66f)
            val stroke = r * 0.10f
            val topLeft = Offset(c.x - r, c.y - r)
            val arcSize = Size(2 * r, 2 * r)
            val frac = (anim / maxSpeed).coerceIn(0f, 1f)

            drawArc(
                color = Palette.Track, startAngle = 160f, sweepAngle = 220f, useCenter = false,
                topLeft = topLeft, size = arcSize, style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (frac > 0.005f) {
                rotate(degrees = 160f, pivot = c) {
                    val brush = Brush.sweepGradient(
                        0f to Color(0xFF1B5CC8),
                        0.45f to Color(0xFF2F8BFF),
                        0.61f to Color(0xFF7FD4FF),
                        1f to Color(0xFF7FD4FF),
                        center = c,
                    )
                    drawArc(
                        brush = brush, startAngle = 0f, sweepAngle = 220f * frac, useCenter = false,
                        topLeft = topLeft, size = arcSize, alpha = 0.25f,
                        style = Stroke(width = stroke * 2.2f, cap = StrokeCap.Round),
                    )
                    drawArc(
                        brush = brush, startAngle = 0f, sweepAngle = 220f * frac, useCenter = false,
                        topLeft = topLeft, size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
        }

        Column(
            modifier = Modifier.offset(y = (h * 0.02f).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = speed?.toString() ?: "--",
                color = Palette.Text,
                fontSize = (h * 0.42f).sp,
                fontWeight = FontWeight.ExtraBold,
                fontStyle = FontStyle.Italic,
            )
            Text(
                text = "km/h",
                color = Palette.BlueBright,
                fontSize = (h * 0.13f).sp,
                fontWeight = FontWeight.Bold,
                fontStyle = FontStyle.Italic,
            )
        }
    }
}

// ============================================================================ TARJETAS

data class CardSpec(
    val icon: Ico,
    val label: String,
    val value: String,
    val unit: String,
    val progress: Float? = null,
    val accent: Color = Palette.Cyan,
)

/** Tarjeta con icono, etiqueta, valor grande y barra de progreso. El texto se escala con su altura. */
@Composable
fun InfoCard(spec: CardSpec, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    BoxWithConstraints(
        modifier = modifier
            .clip(shape)
            .background(Brush.linearGradient(listOf(Palette.CardTop, Palette.CardBottom)))
            .border(
                1.dp,
                Brush.linearGradient(listOf(Palette.Blue.copy(alpha = 0.85f), Palette.Blue.copy(alpha = 0.10f))),
                shape,
            ),
    ) {
        val base = maxHeight.value.coerceIn(56f, 130f)
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = (base * 0.15f).dp, vertical = (base * 0.07f).dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy((base * 0.12f).dp),
        ) {
            ObdIcon(spec.icon, Modifier.size((base * 0.42f).dp), spec.accent)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(
                    text = spec.label,
                    color = Palette.TextDim,
                    fontSize = (base * 0.14f).sp,
                    maxLines = 1,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = spec.value,
                        color = Palette.Text,
                        fontSize = (base * 0.34f).sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = spec.unit,
                        color = Palette.TextDim,
                        fontSize = (base * 0.15f).sp,
                        maxLines = 1,
                        modifier = Modifier.padding(bottom = (base * 0.05f).dp),
                    )
                }
                spec.progress?.let {
                    Spacer(Modifier.height((base * 0.04f).dp))
                    ProgressBar(it, spec.accent)
                }
            }
        }
    }
}

@Composable
fun ProgressBar(frac: Float, accent: Color = Palette.Cyan, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(CircleShape)
            .background(Palette.Track),
    ) {
        Box(
            Modifier
                .fillMaxWidth(frac.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(Palette.Blue, accent))),
        )
    }
}

/** Valor entero redondeado, útil para los PIDs en flotante. */
fun Float.roundInt(): Int = roundToInt()
