package io.github.superthom196.matv

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import io.github.superthom196.matv.ui.AppRoot
import io.github.superthom196.matv.ui.HiFiTheme

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()
    private var playbackSession: PlaybackSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HiFiTheme { AppRoot(vm) }
        }
        playbackSession = PlaybackSession(this, vm, this)
    }

    override fun onDestroy() {
        playbackSession?.release(); playbackSession = null
        super.onDestroy()
    }

    /**
     * Media keys on the TV remote drive the selected hi-fi from every screen, before Compose
     * sees the event. D-pad and Back are left alone so on-screen focus keeps working.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in dpadKeys) io.github.superthom196.matv.ui.DpadTracker.stamp()
        // Volume first, and repeats included so holding a button ramps.
        //
        // Channel up / down are the ones that actually reach us on a Bravia: Sony's platform takes
        // the volume rocker below the app and drives the TV's own speakers with it, so those keys
        // are never dispatched here at all. The VOLUME_UP/DOWN arm is kept for TVs that do deliver
        // them; on this one it simply never fires.
        val volumeStep = when (event.keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_VOLUME_UP -> 1
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN -> -1
            else -> 0
        }
        if (volumeStep != 0) {
            if (event.action == KeyEvent.ACTION_DOWN) vm.nudgeVolume(volumeStep)
            return true
        }
        // A Bravia delivers MUTE but swallows VOLUME_MUTE, the same way it swallows the volume
        // rocker. Both are handled so either remote convention works.
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE || event.keyCode == KeyEvent.KEYCODE_MUTE) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) vm.toggleMute()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> { vm.playPause(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { vm.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { vm.pause(); return true }
                KeyEvent.KEYCODE_MEDIA_STOP -> { vm.stop(); return true }
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> { vm.next(); return true }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> { vm.previous(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { vm.seekRelative(15); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { vm.seekRelative(-15); return true }
            }
        }
        // Swallow the key-up of media keys too, so nothing else reacts to them.
        if (event.action == KeyEvent.ACTION_UP && event.keyCode in mediaKeys) return true
        return super.dispatchKeyEvent(event)
    }

    // Directional keys only: OK opens folders and the resulting list swap must not count as tab navigation.
    private val dpadKeys = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)

    private val mediaKeys = setOf(
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
        KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
    )
}
