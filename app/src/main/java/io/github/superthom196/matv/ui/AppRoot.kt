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
import androidx.compose.runtime.remember
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

/** Screens inside the connected app. A plain in-memory back stack; Back pops, exits at the root. */
sealed class MainScreen {
    data object Library : MainScreen()
    data object Players : MainScreen()
    data object NowPlaying : MainScreen()
    data class Detail(val item: MediaItem) : MainScreen()
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
    when (val s = nav.current) {
        MainScreen.Library -> LibraryScreen(vm, ui, nav)
        MainScreen.Players -> PlayersScreen(vm, ui, onDone = {
            if (nav.stack.size > 1) nav.pop() else nav.replaceRoot(MainScreen.Library)
        })
        MainScreen.NowPlaying -> NowPlayingScreen(vm, ui, nav)
        is MainScreen.Detail -> DetailScreen(vm, ui, nav, s.item)
    }
}
