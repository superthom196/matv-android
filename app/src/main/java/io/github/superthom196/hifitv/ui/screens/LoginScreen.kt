package io.github.superthom196.hifitv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.superthom196.hifitv.AppViewModel
import io.github.superthom196.hifitv.UiState
import io.github.superthom196.hifitv.ui.HSpace
import io.github.superthom196.hifitv.ui.HiFiColors
import io.github.superthom196.hifitv.ui.PillButton
import io.github.superthom196.hifitv.ui.SectionLabel
import io.github.superthom196.hifitv.ui.TvTextField
import io.github.superthom196.hifitv.ui.VSpace

@Composable
fun LoginScreen(vm: AppViewModel, ui: UiState) {
    val server = ui.pendingServer
    var user by rememberSaveable { mutableStateOf(ui.username ?: "") }
    var pass by rememberSaveable { mutableStateOf("") }
    val userFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { userFocus.requestFocus() } }
    BackHandler { vm.backToConnect() }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.width(640.dp)) {
            Text("Sign in", style = MaterialTheme.typography.displaySmall)
            VSpace(6.dp)
            Text("${server?.info?.name ?: "Music Assistant"}  ·  ${server?.baseUrl}  ·  v${server?.info?.serverVersion}",
                style = MaterialTheme.typography.bodyMedium, color = HiFiColors.Muted)
            VSpace(10.dp)
            Text("Use the same username and password as the Music Assistant web app. You only do this once; the TV keeps its own login.",
                style = MaterialTheme.typography.bodySmall, color = HiFiColors.Muted)
            VSpace(36.dp)
            SectionLabel("Username")
            VSpace(8.dp)
            TvTextField(user, { user = it }, placeholder = "username", modifier = Modifier.fillMaxWidth().focusRequester(userFocus))
            VSpace(20.dp)
            SectionLabel("Password")
            VSpace(8.dp)
            TvTextField(pass, { pass = it }, placeholder = "password", password = true, imeAction = ImeAction.Done,
                modifier = Modifier.fillMaxWidth(), onDone = { vm.login(user, pass) })
            VSpace(28.dp)
            Row {
                PillButton(if (ui.loginBusy) "Signing in…" else "Sign in", onClick = { vm.login(user, pass) }, primary = true, enabled = !ui.loginBusy)
                HSpace(16.dp)
                PillButton("Back", onClick = { vm.backToConnect() })
            }
            if (ui.loginError != null) {
                VSpace(20.dp)
                Text(ui.loginError, style = MaterialTheme.typography.bodyMedium, color = HiFiColors.Danger)
            }
        }
    }
}
