package com.arshe.tvos.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.arshe.tvos.R

// Tokens from the "J · Витрина" design (canvas px == dp inside DesignCanvas).
object J {
    val Bg = Color(0xFF000000)
    val Panel = Color(0xFF141414)
    val Skeleton = Color(0xFF1A1A1A)
    val Button = Color.White.copy(alpha = 0.18f)
    val Focus = Color.White
    val Accent = Color(0xFFE5383B)
    val On = Color(0xFF7BE0A8)

    const val CONTENT_X = 168
    const val SAFE_X = 96
    const val SAFE_Y = 54

    val FocusSpring = CubicBezierEasing(.3f, 1.25f, .5f, 1f)
    val ButtonSpring = CubicBezierEasing(.3f, 1.3f, .5f, 1f)
    val Glide = CubicBezierEasing(.22f, 1f, .36f, 1f)
    val SheetIn = CubicBezierEasing(.2f, .9f, .25f, 1f)
    val CopyIn = CubicBezierEasing(.2f, .8f, .2f, 1f)
}

private fun manrope(weight: Int) = Font(
    R.font.manrope,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Manrope = FontFamily(
    manrope(400), manrope(500), manrope(600), manrope(700), manrope(800),
)

fun jText(
    size: Int,
    weight: Int,
    color: Color = Color.White,
    tracking: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
) = TextStyle(
    fontFamily = Manrope,
    fontSize = size.sp,
    fontWeight = FontWeight(weight),
    color = color,
    letterSpacing = tracking,
    lineHeight = lineHeight,
    fontFeatureSettings = "tnum",
)

val TitleTracking = (-0.035).em

/**
 * Lays the UI out on a fixed 1920×1080 canvas, so every number from the design
 * can be used as-is in dp/sp regardless of the panel's real density.
 */
@Composable
fun DesignCanvas(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(J.Bg)) {
        val scale = constraints.maxWidth / 1920f
        CompositionLocalProvider(LocalDensity provides Density(scale, 1f)) {
            Box(Modifier.size(1920.dp, 1080.dp)) { content() }
        }
    }
}
