package io.github.munzzyy.jackdaw.ui.icons

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.munzzyy.jackdaw.engine.AppRow
import io.github.munzzyy.jackdaw.ui.LocalEngine
import io.github.munzzyy.jackdaw.ui.text.avatarColorIndex
import io.github.munzzyy.jackdaw.ui.text.avatarLetter

private object IconCache {
    private const val MAX_BYTES = 8 * 1024 * 1024

    val bitmaps = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Remembers that the engine had no icon, so a long list does not ask again on every scroll. */
    val missing = LruCache<String, Boolean>(2048)
}

/** Every colour carries white text at 4.5:1 or better. */
private val AVATAR_COLORS = listOf(
    Color(0xFF3D5A80), Color(0xFF5E4A8C), Color(0xFF1F6A58), Color(0xFF8A4516), Color(0xFF962D4B),
    Color(0xFF285C86), Color(0xFF52632A), Color(0xFF743A74), Color(0xFF2F6666), Color(0xFF86352A),
)

@Composable
fun AppIcon(row: AppRow, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val engine = LocalEngine.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val key = "${row.id}@$px@${row.installed?.versionCode}"
    val bitmap by produceState(IconCache.bitmaps.get(key), key) {
        if (value == null && IconCache.missing.get(key) == null) {
            val loaded = runCatching { engine.icon(row, px) }.getOrNull()
            if (loaded != null) IconCache.bitmaps.put(key, loaded) else IconCache.missing.put(key, true)
            value = loaded
        }
    }
    val image = bitmap
    val shaped = modifier.size(size).clip(CircleShape).clearAndSetSemantics { }
    if (image != null) {
        Image(image.asImageBitmap(), contentDescription = null, modifier = shaped)
    } else {
        LetterAvatar(row.id, row.config.name, size, shaped)
    }
}

@Composable
fun LetterAvatar(id: String, name: String, size: Dp, modifier: Modifier = Modifier) {
    val bg = AVATAR_COLORS[avatarColorIndex(id, AVATAR_COLORS.size)]
    Box(modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(
            avatarLetter(name),
            color = Color.White,
            fontWeight = FontWeight.Medium,
            fontSize = with(LocalDensity.current) { (size * 0.45f).toSp() },
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
