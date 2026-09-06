package io.github.superthom196.matv

import android.content.Context
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlin.math.abs
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Publishes what the selected player is doing as a system MediaSession, so the remote's media keys
 * keep working when another app is in front and the TV's home screen can show a Now Playing card.
 * Lives as long as the activity; commands go straight to the ViewModel.
 *
 * The session also claims remote volume, which is what makes the TV remote's volume rocker drive the
 * hi-fi instead of the TV's own speakers. The provider's scale is [TV_VOLUME_STEPS] notches, so a full
 * TV volume bar is 100% on the hi-fi and each press moves it by 100 / [TV_VOLUME_STEPS].
 */
class PlaybackSession(context: Context, private val vm: AppViewModel, owner: LifecycleOwner) {
    private val session = MediaSession(context, "MATV").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() { vm.play() }
            override fun onPause() { vm.pause() }
            override fun onStop() { vm.stop() }
            override fun onSkipToNext() { vm.next() }
            override fun onSkipToPrevious() { vm.previous() }
        })
    }

    private val volumeProvider = object : VolumeProvider(VOLUME_CONTROL_ABSOLUTE, TV_VOLUME_STEPS, 0) {
        /** The TV asks for an absolute notch, e.g. when the user drags the system volume bar. */
        override fun onSetVolumeTo(volume: Int) = sendVolume(volume)

        /** One press of volume up / down on the remote. */
        override fun onAdjustVolume(direction: Int) {
            if (direction != 0) sendVolume(currentVolume + direction)
        }
    }

    /** Push a notch value to the hi-fi, and echo it back to the TV so its overlay tracks. */
    private fun sendVolume(notches: Int) {
        val clamped = notches.coerceIn(0, TV_VOLUME_STEPS)
        volumeProvider.currentVolume = clamped
        vm.setVolume(clamped * 100 / TV_VOLUME_STEPS)
    }

    // What was last published, so metadata and state are only rebuilt when they actually change —
    // not every time vm.ui or vm.position emits.
    private var lastState: Int = PlaybackState.STATE_NONE
    private var lastPos: Position? = null
    private var lastMeta: NowPlaying? = null

    /** The MediaSession STATE_* for what vm.ui says right now. */
    private fun stateOf(np: NowPlaying): Int = when {
        !np.hasMedia -> PlaybackState.STATE_STOPPED
        np.state == "playing" -> PlaybackState.STATE_PLAYING
        np.state == "paused" -> PlaybackState.STATE_PAUSED
        else -> PlaybackState.STATE_STOPPED
    }

    private fun publishState(stateCode: Int, positionMs: Long, playing: Boolean) {
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(stateCode, positionMs, if (playing) 1f else 0f)
                .build(),
        )
    }

    init {
        session.setPlaybackToRemote(volumeProvider)
        owner.lifecycleScope.launch {
            // Metadata, volume and active-ness: none of these tick on their own, so this collector
            // only has to react when vm.ui actually changes.
            vm.ui.collect { ui ->
                // Follow the hi-fi: it also moves from the app's own volume row and other controllers.
                val notches = ((ui.selectedPlayer?.volumeLevel ?: 0) * TV_VOLUME_STEPS + 50) / 100
                val clamped = notches.coerceIn(0, TV_VOLUME_STEPS)
                if (volumeProvider.currentVolume != clamped) volumeProvider.currentVolume = clamped

                val np = ui.nowPlaying
                val meta = lastMeta
                if (np.hasMedia && (meta == null || meta.title != np.title || meta.artist != np.artist || meta.album != np.album || meta.duration != np.duration)) {
                    session.setMetadata(
                        MediaMetadata.Builder()
                            .putString(MediaMetadata.METADATA_KEY_TITLE, np.title)
                            .putString(MediaMetadata.METADATA_KEY_ARTIST, np.artist)
                            .putString(MediaMetadata.METADATA_KEY_ALBUM, np.album)
                            .putLong(MediaMetadata.METADATA_KEY_DURATION, ((np.duration ?: 0.0) * 1000).toLong())
                            .build(),
                    )
                    lastMeta = np
                }

                val stateCode = stateOf(np)
                if (stateCode != lastState) {
                    lastState = stateCode
                    publishState(stateCode, (vm.position.value.now() * 1000).toLong(), np.isPlaying)
                }

                // Active whenever a player is chosen, not just while something plays, so the volume
                // keys still reach the hi-fi when it is sitting idle.
                session.isActive = ui.selectedPlayerId != null
            }
        }
        owner.lifecycleScope.launch {
            // vm.position moves roughly once a second while playing, but MediaSession already
            // extrapolates a position at speed 1f on its own — so only drift (the hi-fi's clock
            // disagreeing with ours) or a play/pause flip need a new state object here.
            vm.position.collect { pos ->
                val expected = lastPos?.now()
                val drifted = expected != null && abs(pos.now() - expected) > 2.0
                if (lastPos == null || pos.playing != lastPos?.playing || drifted) {
                    publishState(lastState, (pos.now() * 1000).toLong(), pos.playing)
                }
                lastPos = pos
            }
        }
    }

    fun release() { session.isActive = false; session.release() }
}
