@file:OptIn(ExperimentalTextApi::class)

package com.craigeley.chat.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.craigeley.chat.R

object ChatColors {
    val background = Color.Black
    val onSurface = Color.White
    val onSurfaceVariant = Color.White.copy(alpha = 0.7f)
    val onSurfaceDim = Color.White.copy(alpha = 0.5f)
    val onSurfaceDisabled = Color.White.copy(alpha = 0.3f)
}

val PublicSans = FontFamily(
    Font(
        R.font.publicsans_variablefont_wght,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        R.font.publicsans_variablefont_wght,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    // Heavy weight, used by the text tapbacks (haha / !! / ?) so they hold their own
    // next to the solid filled glyphs (heart/thumbs).
    Font(
        R.font.publicsans_variablefont_wght,
        weight = FontWeight.ExtraBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(800)),
    ),
)

object ChatType {
    val title = TextStyle(
        fontFamily = PublicSans,
        fontSize = 32.sp,
        fontWeight = FontWeight.Normal,
    )
    val button = TextStyle(
        fontFamily = PublicSans,
        fontSize = 32.sp,
        fontWeight = FontWeight.Normal,
    )
    val body = TextStyle(
        fontFamily = PublicSans,
        fontSize = 20.sp,
        fontWeight = FontWeight.Normal,
    )
    val meta = TextStyle(
        fontFamily = PublicSans,
        fontSize = 16.sp,
        fontWeight = FontWeight.Normal,
    )
    val hint = TextStyle(
        fontFamily = PublicSans,
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
    )
}

object ChatDimens {
    val screenPadding = 24.dp
}

@Composable
fun ChatTheme(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, fontScale = 0.85f),
    ) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                background = ChatColors.background,
                surface = ChatColors.background,
                onBackground = ChatColors.onSurface,
                onSurface = ChatColors.onSurface,
                primary = ChatColors.onSurface,
            ),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = ChatColors.background,
                content = content,
            )
        }
    }
}
