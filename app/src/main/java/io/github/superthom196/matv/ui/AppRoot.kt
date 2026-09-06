package io.github.superthom196.matv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.superthom196.matv.AppViewModel
import io.github.superthom196.matv.Phase
import io.github.superthom196.matv.ma.ConnectionState
import io.github.superthom196.matv.ma.MediaItem
import io.github.superthom196.matv.ui.screens.ConnectScreen
import io.github.superthom196.matv.ui.screens.DetailScreen
import io.github.superthom196.matv.ui.screens.LibraryScreen
import io.github.superthom196.matv.ui.screens.LoadingScreen
import io.github.superthom196.matv.ui.screens.LoginScreen
import io.github.superthom196.matv.ui.screens.NowPlayingScreen
import io.github.superthom196.matv.ui.screens.PlayersScreen
import io.github.superthom196.matv.ui.screens.QueueScreen
import io.github.superthom196.matv.ui.screens.SearchScreen
import io.github.superthom196.matv.ui.screens.GenreAlbumsScreen
import io.github.superthom196.matv.ui.screens.SettingsScreen

/** Screens inside the connected app. A plain in-memory back stack; Back pops, exits at the root. */
sealed class MainScreen {
    data object Library : MainScreen()
    data object Players : MainScreen()
    data object NowPlaying : MainScreen()
    data object Queue : MainScreen()
    data object Search : MainScreen()
    data object Settings : MainScreen()
    data class Detail(val item: MediaItem) : MainScreen()
    data class Genre(val name: String) : MainScreen()
}

class Nav(initial: MainScreen) {
    val stack = mutableStateListOf(initial)
    val current: MainScreen get() = stack.last()
    fun push(s: MainScreen) { if (stack.last() != s) stack.add(s) }
    fun pop(): Boolean { if (stack.size > 1) { stack.removeAt(stack.lastIndex); return true }; return false }
    fun replaceRoot(s: MainScreen) { stack.clear(); stack.add(s) }
}

@Composable
fun AppRoot(vm: AppViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(HiFiColors.Background)) {
        when (ui.phase) {
            Phase.Loading -> LoadingScreen(ui)
            Phase.Connect -> ConnectScreen(vm, ui)
            Phase.Login -> LoginScreen(vm, ui)
            Phase.Main -> MainFlow(vm)
        }
        // Volume dial: pops up over everything on any volume or mute change, then clears itself.
        val hud by vm.volumeHud.collectAsStateWithLifecycle()
        hud?.let { VolumeDial(it, Modifier.align(Alignment.Center)) }
        // Connection badge: visible whenever the socket is not simply "connected".
        val conn = ui.connection
        if (ui.phase == Phase.Main && conn !is ConnectionState.Connected) {
            val text = when (conn) {
                is ConnectionState.Reconnecting -> "Reconnecting to ${ui.server?.name ?: "server"}… (${conn.attempt})"
                is ConnectionState.Connecting -> "Connecting…"
                is ConnectionState.Failed -> conn.reason
                else -> "Disconnected"
            }
            Row(
                Modifier.align(Alignment.TopEnd).padding(24.dp)
                    .background(HiFiColors.Danger.copy(alpha = 0.9f), RoundedCornerShape(50))
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            ) { Text(text, style = MaterialTheme.typography.labelMedium, color = HiFiColors.Text) }
        }
        // After a few seconds of not being connected, take over the screen with a proper explanation.
        if (ui.phase == Phase.Main && conn !is ConnectionState.Connected) {
            var stale by remember { mutableStateOf(conn is ConnectionState.Failed) }
            // The first connect happens behind the cached library, so give it longer before taking
            // over the screen; after a connection has been lost, five seconds is right.
            LaunchedEffect(conn) { if (conn !is ConnectionState.Failed) { stale = false; delay(if (ui.everConnected) 5000 else 20000) }; stale = true }
            if (stale) ConnectionOverlay(vm, ui)
        }
        // Transient message (command feedback, errors).
        AnimatedVisibility(visible = ui.message != null, modifier = Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.padding(bottom = 40.dp)
                    .background(HiFiColors.SurfaceHigh, RoundedCornerShape(50))
                    .padding(horizontal = 26.dp, vertical = 12.dp),
            ) { Text(ui.message ?: "", style = MaterialTheme.typography.labelLarge) }
        }
    }
}

@Composable
private fun MainFlow(vm: AppViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val nav = remember { Nav(if (ui.selectedPlayerId == null) MainScreen.Players else MainScreen.Library) }
    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
    // Screens are swapped, not stacked, so a screen leaving composition would lose every
    // rememberSaveable it owns — which sent you back to the default tab, and to the top of the
    // grid, every time you backed out of an album. Holding the state per screen keeps your place.
    val stateHolder = rememberSaveableStateHolder()
    val s = nav.current
    stateHolder.SaveableStateProvider(screenKey(s)) {
        when (s) {
            MainScreen.Library -> LibraryScreen(vm, ui, nav)
            MainScreen.Players -> PlayersScreen(vm, ui, onDone = {
                if (nav.stack.size > 1) nav.pop() else nav.replaceRoot(MainScreen.Library)
            })
            MainScreen.NowPlaying -> NowPlayingScreen(vm, ui, nav)
            MainScreen.Queue -> QueueScreen(vm, ui, nav)
            MainScreen.Search -> SearchScreen(vm, ui, nav)
            MainScreen.Settings -> SettingsScreen(vm, ui, nav)
            is MainScreen.Detail -> DetailScreen(vm, ui, nav, s.item)
            is MainScreen.Genre -> GenreAlbumsScreen(vm, nav, s.name)
        }
    }
}

/** Stable identity for one screen's saved state; each album keeps its own. */
private fun screenKey(s: MainScreen): String = when (s) {
    is MainScreen.Detail -> "detail:${s.item.uri ?: "${s.item.provider}:${s.item.itemId}"}"
    is MainScreen.Genre -> "genre:${s.name}"
    else -> s::class.simpleName ?: "screen"
}


@Composable
private fun ConnectionOverlay(vm: AppViewModel, ui: io.github.superthom196.matv.UiState) {
    val retry = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { retry.requestFocus() } }
    val conn = ui.connection
    Box(Modifier.fillMaxSize().background(HiFiColors.Background.copy(alpha = 0.94f)), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Lost contact with ${ui.server?.name ?: "Music Assistant"}", style = MaterialTheme.typography.headlineMedium)
            VSpace(8.dp)
            Text(
                when (conn) {
                    is ConnectionState.Reconnecting -> "Trying to reconnect (attempt ${conn.attempt}). ${conn.reason}"
                    is ConnectionState.Failed -> conn.reason
                    else -> "Not connected."
                },
                style = MaterialTheme.typography.bodyMedium, color = HiFiColors.Muted,
            )
            Text(ui.baseUrl ?: "", style = MaterialTheme.typography.bodySmall, color = HiFiColors.Muted)
            VSpace(24.dp)
            Row {
                PillButton("Retry now", onClick = { vm.reconnectNow() }, primary = true, modifier = Modifier.focusRequester(retry))
                HSpace(12.dp)
                PillButton("Change server", onClick = { vm.forgetServer() })
            }
        }
    }
}
