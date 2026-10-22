package com.mundymorning.touchreader.ui.art

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mundymorning.touchreader.core.art.ArtFrames
import com.mundymorning.touchreader.core.art.NineSlice
import com.mundymorning.touchreader.ui.theme.OnLeather

/*
 * Artwork slots (see docs/ASSETS.md). A picture for a slot is optional: pictures are found by name at run time
 * (res/drawable-nodpi/art_<slot>, copied there from art/<page>/<slot>.png by the build), and a slot with no picture,
 * or one that cannot be decoded, quietly falls back to the app's built-in look. Nothing here can break the app
 * because a picture is missing, replaced or wrong.
 */
object Art {
    private val cache = HashMap<String, Int>()

    /** The resource id of the picture for [slot], or 0 if there is none that can be used. */
    fun resId(context: Context, slot: String): Int = synchronized(cache) { cache.getOrPut(slot) { find(context, slot) } }

    private fun find(context: Context, slot: String): Int {
        val id = context.resources.getIdentifier("art_$slot", "drawable", context.packageName)
        if (id == 0) return 0
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeResource(context.resources, id, bounds)
            if (bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096) id else 0
        }.getOrDefault(0)
    }
}

@Composable
fun artId(slot: String): Int {
    val context = LocalContext.current
    return remember(slot) { Art.resId(context, slot) }
}

@Composable
fun hasArt(slot: String): Boolean = artId(slot) != 0

@Composable
private fun artBitmap(slot: String): ImageBitmap? {
    val id = artId(slot)
    return if (id == 0) null else ImageBitmap.imageResource(id)
}

/** The picture for [slot], or [fallbackRes] (a built-in picture), or nothing. */
@Composable
fun ArtImage(slot: String, contentDescription: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop, fallbackRes: Int = 0) {
    val id = artId(slot)
    val res = if (id != 0) id else fallbackRes
    if (res != 0) Image(painterResource(res), contentDescription, modifier, contentScale = contentScale)
}

/** A decorative strip (banner or divider) that fills the width, or nothing at all if there is no picture. */
@Composable
fun ArtBanner(slot: String, height: Dp, modifier: Modifier = Modifier) {
    if (hasArt(slot)) ArtImage(slot, null, modifier.fillMaxWidth().height(height), ContentScale.Crop)
}

/**
 * An icon: the picture for [slot] when there is one, otherwise the standard [fallback] icon. Disabled icons are greyed and faded.
 * [size] is in dp (the pictures are made for 3x, so 192 px shows at 64 dp unless a smaller size is asked for).
 */
@Composable
fun ArtIcon(slot: String, fallback: ImageVector, contentDescription: String?, modifier: Modifier = Modifier, size: Dp = 24.dp, enabled: Boolean = true) {
    val id = artId(slot)
    if (id != 0) {
        val grey = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
        Image(
            painterResource(id), contentDescription, modifier.size(size).alpha(if (enabled) 1f else 0.4f),
            contentScale = ContentScale.Fit, colorFilter = if (enabled) null else grey,
        )
    } else Icon(fallback, contentDescription, modifier.size(size), tint = if (enabled) LocalContentColor.current else LocalContentColor.current.copy(alpha = 0.4f))
}

/** Repeats the picture for [slot] behind this element, or leaves it alone when there is no picture. */
@Composable
fun Modifier.artTexture(slot: String): Modifier {
    val bitmap = artBitmap(slot)
    val brush = remember(bitmap) { bitmap?.let { ShaderBrush(ImageShader(it, TileMode.Repeated, TileMode.Repeated)) } }
    return if (brush == null) this else this.drawBehind { drawRect(brush) }
}

/**
 * Draws the stretchable frame picture for [slot] behind this element (corners keep their shape, edges and middle stretch), or leaves it
 * alone when there is no picture. Corner size follows the screen: the pictures are made for a 3x screen.
 */
@Composable
fun Modifier.artFrame(slot: String): Modifier {
    val bitmap = artBitmap(slot)
    val spec = ArtFrames.all[slot]
    val density = LocalDensity.current.density
    if (bitmap == null || spec == null) return this
    // a picture made larger than the sheet asks (up to double) is cut at the matching place
    val slice = (spec.slice * bitmap.width.toFloat() / spec.width).toInt().coerceAtLeast(1)
    val corner = spec.slice * density / 3f
    return this.drawBehind {
        for (p in NineSlice.pieces(bitmap.width, bitmap.height, slice, size.width.toInt(), size.height.toInt(), corner)) {
            drawImage(bitmap, IntOffset(p.src.x, p.src.y), IntSize(p.src.w, p.src.h), IntOffset(p.dst.x, p.dst.y), IntSize(p.dst.w, p.dst.h))
        }
    }
}

/**
 * A button. With the button pictures present it is drawn on the brass-and-leather frame (normal, pressed, selected or disabled, each
 * with its own picture when there is one, otherwise the normal one adjusted); without them it is the standard tonal button.
 */
@Composable
fun ArtButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    /** Only matters without the pictures: the outlined look instead of the filled one. */
    outlined: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    if (!hasArt("global_button_frame")) {
        if (outlined) OutlinedButton(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, content = content)
        else FilledTonalButton(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, content = content)
        return
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val wanted = when { !enabled -> "global_button_frame_disabled"; pressed -> "global_button_frame_pressed"; selected -> "global_button_frame_selected"; else -> "global_button_frame" }
    val usable = hasArt(wanted)
    val frame = if (usable) wanted else "global_button_frame"
    val fade = if (usable) 1f else when { !enabled -> 0.45f; pressed -> 0.85f; else -> 1f }
    OnLeather {
        Row(
            modifier.heightIn(min = 48.dp).alpha(fade).artFrame(frame)
                .clickable(interaction, null, enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
