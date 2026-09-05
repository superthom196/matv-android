package io.github.superthom196.matv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.superthom196.matv.AppViewModel
import io.github.superthom196.matv.ma.MediaItem

/** A row in a small action menu. */
data class MenuAction(val label: String, val icon: ImageVector? = null, val danger: Boolean = false, val onPick: () -> Unit)

/** Generic D-pad action menu: title + a column of actions, first one focused. */
@Composable
fun ActionMenu(title: String, subtitle: String? = null, actions: List<MenuAction>, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    // The menu opens on the long-press *down*; the matching key-up would otherwise click the first
    // row. Swallow OK/Enter key-ups until a fresh key-down has been seen inside the menu.
    var armed by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(420.dp)
                .onPreviewKeyEvent { ev ->
                    if (armed) return@onPreviewKeyEvent false
                    if (ev.type == KeyEventType.KeyDown) { armed = true; false }
                    else if (ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter) { armed = true; true }
                    else false
                }
                .background(HiFiColors.Surface, RoundedCornerShape(18.dp)).padding(20.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = HiFiColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            VSpace(12.dp)
            actions.forEachIndexed { i, a ->
                FocusSurface(
                    onClick = { onDismiss(); a.onPick() },
                    modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp), container = HiFiColors.Surface, scale = 1.0f,
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (a.icon != null) { Icon(a.icon, null, tint = if (a.danger) HiFiColors.Danger else HiFiColors.Accent, modifier = Modifier.size(22.dp)); HSpace(12.dp) }
                        Text(a.label, style = MaterialTheme.typography.bodyLarge, color = if (a.danger) HiFiColors.Danger else HiFiColors.Text)
                    }
                }
            }
        }
    }
}

/**
 * Long-press menu for anything playable: play now / next / add to queue / shuffle / favourite.
 * `playNow` lets callers keep their own "play now" semantics (e.g. a folder track plays the rest of the folder).
 */
@Composable
fun PlayOptionsMenu(vm: AppViewModel, item: MediaItem, onDismiss: () -> Unit, playNow: (() -> Unit)? = null) {
    val overrides by vm.favOverrides.collectAsStateWithLifecycle()
    val fav = overrides["${item.provider}:${item.itemId}"] ?: item.favorite
    val sub = when (item.mediaType) {
        "album", "track" -> item.artistLine
        else -> item.mediaType.replace('_', ' ')
    }
    val actions = buildList {
        if (item.isPlayable && item.uri != null) {
            add(MenuAction("Play now", Icons.Default.PlayArrow) { playNow?.invoke() ?: vm.playItem(item) })
            add(MenuAction("Play next", Icons.Default.QueueMusic) { vm.playItem(item, option = "next") })
            add(MenuAction("Add to queue", Icons.Default.PlaylistAdd) { vm.playItem(item, option = "add") })
            if (item.mediaType in setOf("album", "artist", "playlist", "folder")) add(MenuAction("Shuffle", Icons.Default.Shuffle) { vm.playItem(item); vm.shuffleOn() })
        }
        if (item.mediaType in setOf("artist", "album", "track", "playlist", "radio")) {
            add(MenuAction(if (fav) "Remove from favourites" else "Add to favourites", if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder) { vm.toggleFavourite(item) })
        }
    }
    ActionMenu(item.name, sub, actions, onDismiss)
}

/** Yes/no confirmation. */
@Composable
fun ConfirmMenu(title: String, subtitle: String?, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ActionMenu(title, subtitle, listOf(MenuAction(confirmLabel, danger = true) { onConfirm() }, MenuAction("Cancel") { }), onDismiss)
}
