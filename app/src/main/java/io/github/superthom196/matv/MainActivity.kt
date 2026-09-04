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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HiFiTheme { AppRoot(vm) }
        }
    }

    /**
     * Media keys on the TV remote drive the selected hi-fi from every screen, before Compose
     * sees the event. D-pad and Back are left alone so on-screen focus keeps working.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
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

    private val mediaKeys = setOf(
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
        KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
    )
}
