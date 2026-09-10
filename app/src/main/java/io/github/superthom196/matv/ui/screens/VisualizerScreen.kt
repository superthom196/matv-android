package io.github.superthom196.matv.ui.screens

import android.opengl.GLSurfaceView
import android.view.Choreographer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.superthom196.matv.AppViewModel
import io.github.superthom196.matv.AuthHolder
import io.github.superthom196.matv.UiState
import io.github.superthom196.matv.ui.Artwork
import io.github.superthom196.matv.ui.HSpace
import io.github.superthom196.matv.ui.HiFiColors
import io.github.superthom196.matv.ui.Nav
import io.github.superthom196.matv.visualizer.IdleBands
import io.github.superthom196.matv.visualizer.TileFieldRenderer
import io.github.superthom196.matv.visualizer.VisualizerFeed
import kotlinx.coroutines.delay

/**
 * Full-screen visualiser: a field of 3D tiles in the album's colours, moving to what the
 * selected player is playing. The audio comes from Music Assistant's MilkDrop Visualizer
 * plugin, which taps the audio the server is already decoding for that player, so it follows
 * whichever player this app is controlling and nothing else. Back leaves.
 */
@Composable
fun VisualizerScreen(vm: AppViewModel, ui: UiState, nav: Nav) {
    val renderer = remember { TileFieldRenderer() }
    var feed by remember { mutableStateOf<VisualizerFeed?>(null) }
    val playerId = ui.selectedPlayerId
    val baseUrl = ui.baseUrl

    // Keep the picture alive: nothing on this screen takes input, so the TV would otherwise dim.
    val view = LocalView.current
    DisposableEffect(view) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }

    // One feed per selected player; a change of player reconnects.
    LaunchedEffect(playerId, baseUrl) {
        feed?.stop(); feed = null; renderer.source = IdleBands
        if (playerId == null || baseUrl == null) return@LaunchedEffect
        val token = AuthHolder.token ?: return@LaunchedEffect
        val url = baseUrl.trimEnd('/').replaceFirst("http", "ws") + "/milkdrop_visualizer"
        feed = VisualizerFeed(url, token, playerId).also { it.start(); renderer.source = it }
    }
    DisposableEffect(Unit) { onDispose { feed?.stop(); renderer.source = IdleBands; renderer.release() } }

    val glView = remember { mutableStateOf<GLSurfaceView?>(null) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> { glView.value?.onPause(); feed?.stop() }
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> { glView.value?.onResume(); feed?.start() }
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    Box(Modifier.fillMaxSize().background(HiFiColors.Background)) {
        AndroidView(
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    glView.value = this
                    setEGLContextClientVersion(3)
                    setEGLConfigChooser(8, 8, 8, 0, 16, 0)
                    setRenderer(renderer)
                    // Paced from the Choreographer at half the panel rate (25 fps on a 50 Hz set,
                    // 30 on 60 Hz): the Bravia's GPU has that much and no more at 1080p.
                    renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { },
        )
        DisposableEffect(Unit) {
            var count = 0
            var running = true
            val cb = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (!running) return
                    if (++count >= 2) { count = 0; glView.value?.requestRender() }
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
            Choreographer.getInstance().postFrameCallback(cb)
            onDispose { running = false; Choreographer.getInstance().removeFrameCallback(cb) }
        }

        // What is playing, floating over the tiles: the cover, with the track and artist beside it,
        // so the picture keeps its context. Lifted off the field by a shadow rather than boxed in.
        val np = ui.nowPlaying
        if (np.hasMedia) {
            val lift = Shadow(Color(0xD0000000), Offset(0f, 2f), blurRadius = 14f)
            Row(
                Modifier.align(Alignment.BottomStart).padding(horizontal = 48.dp, vertical = 40.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Artwork(np.imageUrl, Modifier.size(180.dp).shadow(24.dp, RoundedCornerShape(12.dp)), corner = 12.dp, crossfade = true)
                HSpace(24.dp)
                Column(Modifier.padding(bottom = 6.dp).widthIn(max = 900.dp)) {
                    Text(
                        np.title, style = MaterialTheme.typography.headlineSmall.copy(shadow = lift), color = HiFiColors.Text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    val line = listOf(np.artist, np.album).filter { it.isNotBlank() }.joinToString("  ·  ")
                    if (line.isNotBlank()) {
                        Text(
                            line, style = MaterialTheme.typography.bodyLarge.copy(shadow = lift), color = HiFiColors.Text.copy(alpha = 0.8f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        // The plugin has to be switched on in Music Assistant; say so if the relay is not there.
        val status by (feed?.status ?: remember { kotlinx.coroutines.flow.MutableStateFlow("idle") }).collectAsStateWithLifecycle()
        var showHint by remember { mutableStateOf(false) }
        LaunchedEffect(status) {
            showHint = false
            if (status.startsWith("failed") || status.startsWith("closed")) { delay(6000); showHint = true }
        }
        if (showHint) {
            Text(
                "No visualiser feed. Enable the MilkDrop Visualizer plugin in Music Assistant (Settings › Plugins).",
                style = MaterialTheme.typography.bodyMedium, color = HiFiColors.Muted,
                modifier = Modifier.align(Alignment.BottomCenter).padding(40.dp)
                    .background(HiFiColors.Background.copy(alpha = 0.8f), RoundedCornerShape(50))
                    .padding(horizontal = 22.dp, vertical = 10.dp),
            )
        }
    }
}
