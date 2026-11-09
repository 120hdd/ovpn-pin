package com.erelay.relay.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.erelay.relay.ui.theme.Tone

/*
 * The desktop's flags, drawn the way the desktop draws them: the whole flag
 * filling a circle, cropped to its middle. They are the same files - lipis'
 * flag-icons, MIT, the licence beside them in assets/flags - rasterised from
 * app/ui/flags' SVGs to 144px WebP, because Compose has no SVG of its own.
 *
 * Iran's is the exception: the Lion and Sun flag, from Wikimedia Commons
 * ("State flag of Iran (1964-1980)", public domain), cut to the same 4:3.
 *
 * The emoji they replace sat small in the middle of a grey disc, with the
 * disc showing round it, and every phone drew them in its own hand.
 */

private val flags = HashMap<String, ImageBitmap?>()

/** The flag for a country code, decoded once and kept; null if there is none. */
private fun flagBitmap(context: Context, code: String): ImageBitmap? {
    val c = code.lowercase().take(2)
    if (c.length != 2) return null
    synchronized(flags) {
        if (flags.containsKey(c)) return flags[c]
        // The core folds gb into uk; the files are named the ISO way.
        val got = listOf(c, if (c == "uk") "gb" else c).distinct().firstNotNullOfOrNull { name ->
            try {
                context.assets.open("flags/$name.webp").use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
        flags[c] = got
        return got
    }
}

/** The flag's picture alone, for a background that wants more than a circle of it. */
@Composable
fun rememberFlagBitmap(code: String): ImageBitmap? {
    val context = LocalContext.current
    return remember(code) { flagBitmap(context, code) }
}

/**
 * A country's flag as a filled circle with a hairline edge. A country there
 * is no picture for falls back to its emoji, and no country to a globe.
 */
@Composable
fun Flag(code: String, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap = remember(code) { flagBitmap(context, code) }
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Tone.surface2)
            .border(1.dp, Tone.stroke, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            flagOf(code).isNotEmpty() -> Text(flagOf(code), fontSize = (size.value * 0.55f).sp)
            else -> Icon(Glyph.globe, null, Modifier.size(size * 0.45f), tint = Tone.text2)
        }
    }
}
