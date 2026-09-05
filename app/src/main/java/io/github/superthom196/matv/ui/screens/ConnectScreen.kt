package io.github.superthom196.matv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.superthom196.matv.AppViewModel
import io.github.superthom196.matv.UiState
import io.github.superthom196.matv.ui.FocusSurface
import io.github.superthom196.matv.ui.HSpace
import io.github.superthom196.matv.ui.HiFiColors
import io.github.superthom196.matv.ui.PillButton
import io.github.superthom196.matv.ui.SectionLabel
import io.github.superthom196.matv.ui.StatusDot
import io.github.superthom196.matv.ui.TvTextField
import io.github.superthom196.matv.ui.VSpace
import io.github.superthom196.matv.ui.Wordmark

@Composable
fun LoadingScreen(ui: UiState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Wordmark(logoSize = 48.dp, fontSize = 48.sp)
            VSpace(16.dp)
            Text("Connecting to ${ui.baseUrl ?: "Music Assistant"}…", style = MaterialTheme.typography.bodyLarge, color = HiFiColors.Muted)
        }
    }
}

@Composable
fun ConnectScreen(vm: AppViewModel, ui: UiState) {
    var host by rememberSaveable { mutableStateOf("") }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(ui.discovered.size) { if (ui.discovered.isNotEmpty()) runCatching { firstFocus.requestFocus() } }

    Row(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 32.dp)) {
        Column(Modifier.width(520.dp)) {
            Wordmark(logoSize = 48.dp, fontSize = 48.sp)
            VSpace(8.dp)
            Text("A big-screen remote for Music Assistant.", style = MaterialTheme.typography.bodyLarge, color = HiFiColors.Muted)
            VSpace(40.dp)
            SectionLabel("Or enter the server address")
            VSpace(12.dp)
            TvTextField(host, { host = it }, placeholder = "e.g. 192.168.1.50 or my-server", imeAction = ImeAction.Done, onDone = { vm.connectManual(host) })
            VSpace(16.dp)
            PillButton("Connect", onClick = { vm.connectManual(host) }, primary = true)
            if (ui.connectError != null) {
                VSpace(24.dp)
                Text(ui.connectError, style = MaterialTheme.typography.bodyMedium, color = HiFiColors.Danger)
            }
        }
        HSpace(64.dp)
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(
                    if (ui.discovering) "Looking for Music Assistant on your network…" else "Servers found",
                    modifier = Modifier.weight(1f),
                )
                HSpace(16.dp)
                if (!ui.discovering) PillButton("Scan again", onClick = { vm.startDiscovery() }, icon = Icons.Default.Refresh)
            }
            VSpace(16.dp)
            if (ui.discovered.isEmpty() && !ui.discovering) {
                Text("Nothing found yet. Make sure the TV and the server are on the same network, or type the address on the left.",
                    style = MaterialTheme.typography.bodyMedium, color = HiFiColors.Muted)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 4.dp)) {
                items(ui.discovered, key = { it.info.serverId }) { s ->
                    val mod = if (s == ui.discovered.first()) Modifier.focusRequester(firstFocus) else Modifier
                    FocusSurface(onClick = { vm.chooseServer(s) }, modifier = mod.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 22.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(HiFiColors.Good)
                            HSpace(18.dp)
                            Column {
                                Text(s.info.name ?: "Music Assistant", style = MaterialTheme.typography.titleLarge)
                                Text("${s.baseUrl}  ·  v${s.info.serverVersion}  ·  found via ${s.via}", style = MaterialTheme.typography.bodySmall, color = HiFiColors.Muted)
                            }
                        }
                    }
                }
            }
        }
    }
}
