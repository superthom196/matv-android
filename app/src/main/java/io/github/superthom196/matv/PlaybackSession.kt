package io.github.superthom196.matv

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Publishes what the selected player is doing as a system MediaSession, so the remote's media keys
 * keep working when another app is in front and the TV's home screen can show a Now Playing card.
 * Lives as long as the activity; commands go straight to the ViewModel.
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

    init {
        owner.lifecycleScope.launch {
            vm.ui.collect { ui ->
                val np = ui.nowPlaying
                if (!np.hasMedia) { session.isActive = false; return@collect }
                session.setMetadata(
                    MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, np.title)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, np.artist)
                        .putString(MediaMetadata.METADATA_KEY_ALBUM, np.album)
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, ((np.duration ?: 0.0) * 1000).toLong())
                        .build(),
                )
                val state = when (np.state) { "playing" -> PlaybackState.STATE_PLAYING; "paused" -> PlaybackState.STATE_PAUSED; else -> PlaybackState.STATE_STOPPED }
                session.setPlaybackState(
                    PlaybackState.Builder()
                        .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                        .setState(state, (np.elapsed * 1000).toLong(), if (np.isPlaying) 1f else 0f)
                        .build(),
                )
                session.isActive = true
            }
        }
    }

    fun release() { session.isActive = false; session.release() }
}
