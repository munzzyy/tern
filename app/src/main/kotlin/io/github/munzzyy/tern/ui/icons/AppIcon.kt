package io.github.munzzyy.tern.ui.icons

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.munzzyy.tern.engine.AppRow
import io.github.munzzyy.tern.ui.LocalEngine
import io.github.munzzyy.tern.ui.text.avatarColorIndex
import io.github.munzzyy.tern.ui.text.avatarLetter
import io.github.munzzyy.tern.ui.theme.LocalLook
import io.github.munzzyy.tern.ui.theme.LocalOutlines
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [key] names the picture. [round] changes with every check of the app, and a new round asks again for a picture that was missing. */
internal data class IconAsk(val key: String, val round: String)

internal fun iconAsk(row: AppRow, px: Int, sourceIcons: Boolean): IconAsk =
    IconAsk("${row.id}@$px@${row.installed?.versionCode}@$sourceIcons", row.lastCheckedMs.toString())

/** Remembers in which round the engine had no icon, so a long list does not ask again on every scroll. */
internal class Misses(private val most: Int = 2048) {
    private val rounds = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > most
    }

    @Synchronized
    fun known(ask: IconAsk): Boolean = rounds[ask.key] == ask.round

    @Synchronized
    fun note(ask: IconAsk) {
        rounds[ask.key] = ask.round
    }
}

private object IconCache {
    private const val MAX_BYTES = 8 * 1024 * 1024

    val bitmaps = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    val misses = Misses()
}

/** Every colour carries white text at 4.5:1 or better. */
private val AVATAR_COLORS = listOf(
    Color(0xFF3D5A80), Color(0xFF5E4A8C), Color(0xFF1F6A58), Color(0xFF8A4516), Color(0xFF962D4B),
    Color(0xFF285C86), Color(0xFF52632A), Color(0xFF743A74), Color(0xFF2F6666), Color(0xFF86352A),
)

/** How much of an icon shows through when its app is not installed, as in Obtainium. */
private const val DIMMED = 0.6f

/** Cut to the Icon shape setting. [dimmed] shows it faded, for an app that is not installed. */
@Composable
fun AppIcon(row: AppRow, size: Dp = LocalLook.current.iconList, modifier: Modifier = Modifier, dimmed: Boolean = false) {
    val engine = LocalEngine.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val settings by engine.settings.collectAsStateWithLifecycle()
    val ask = iconAsk(row, px, settings.sourceIcons)
    val bitmap by produceState(IconCache.bitmaps.get(ask.key), ask) {
        value = IconCache.bitmaps.get(ask.key)
        if (value == null && !IconCache.misses.known(ask)) {
            val loaded = try {
                engine.icon(row, px)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (loaded != null) IconCache.bitmaps.put(ask.key, loaded) else IconCache.misses.note(ask)
            value = loaded
        }
    }
    val image = bitmap
    val shaped = modifier.size(size).clip(LocalOutlines.current.icon).alpha(if (dimmed) DIMMED else 1f).clearAndSetSemantics { }
    if (image != null) {
        Image(image.asImageBitmap(), contentDescription = null, modifier = shaped)
    } else {
        LetterAvatar(row.id, row.config.shownName, size, shaped)
    }
}

@Composable
fun LetterAvatar(id: String, name: String, size: Dp, modifier: Modifier = Modifier) {
    val bg = AVATAR_COLORS[avatarColorIndex(id, AVATAR_COLORS.size)]
    Box(modifier.size(size).clip(LocalOutlines.current.icon).background(bg), contentAlignment = Alignment.Center) {
        Text(
            avatarLetter(name),
            color = Color.White,
            fontWeight = FontWeight.Medium,
            fontSize = with(LocalDensity.current) { (size * 0.45f).toSp() },
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/**
 * The colour an app's icon mostly is, for tinting what stands around it, or the colour of its
 * letter when it has no icon. Grey and near-white pixels count for little, so a white icon on a
 * coloured shape gives the shape's colour.
 */
@Composable
fun rememberIconTint(row: AppRow, size: Dp): Color {
    val engine = LocalEngine.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val settings by engine.settings.collectAsStateWithLifecycle()
    val ask = iconAsk(row, px, settings.sourceIcons)
    val letter = AVATAR_COLORS[avatarColorIndex(row.id, AVATAR_COLORS.size)]
    val tint by produceState(letter, ask) {
        val bitmap = IconCache.bitmaps.get(ask.key) ?: if (IconCache.misses.known(ask)) null else try {
            engine.icon(row, px)?.also { IconCache.bitmaps.put(ask.key, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        value = bitmap?.let { withContext(Dispatchers.Default) { dominantColor(it) } } ?: letter
    }
    return tint
}

/** The mean of the icon's coloured pixels, each weighed by how coloured it is; null for an icon of greys. */
internal fun dominantColor(bitmap: Bitmap): Color? {
    val small = Bitmap.createScaledBitmap(bitmap, TINT_SAMPLE, TINT_SAMPLE, true)
    val pixels = IntArray(TINT_SAMPLE * TINT_SAMPLE)
    small.getPixels(pixels, 0, TINT_SAMPLE, 0, 0, TINT_SAMPLE, TINT_SAMPLE)
    if (small !== bitmap) small.recycle()
    return weighedColor(pixels)
}

/** [argb] pixels averaged by how coloured and how opaque each is; null when none has colour enough. */
internal fun weighedColor(argb: IntArray): Color? {
    var r = 0.0
    var g = 0.0
    var b = 0.0
    var total = 0.0
    for (p in argb) {
        val alpha = (p ushr 24 and 0xff) / 255.0
        if (alpha < 0.5) continue
        val red = p shr 16 and 0xff
        val green = p shr 8 and 0xff
        val blue = p and 0xff
        val max = maxOf(red, green, blue)
        val min = minOf(red, green, blue)
        val chroma = (max - min) / 255.0
        if (chroma < 0.12) continue
        val weight = alpha * chroma * chroma
        r += red * weight
        g += green * weight
        b += blue * weight
        total += weight
    }
    if (total <= 0.0) return null
    return Color((r / total).toInt(), (g / total).toInt(), (b / total).toInt())
}

private const val TINT_SAMPLE = 24
