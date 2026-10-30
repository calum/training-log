package com.calum.traininglog

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.calum.traininglog.core.CueTones
import com.calum.traininglog.core.CueType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Plays the cue beeps as generated PCM through an [AudioTrack].
 *
 * Why not ToneGenerator: its loudness is a fixed internal mix that does not track the media volume the same
 * way, and it gives no control over audio focus. Here the track uses USAGE_MEDIA, so it sits at the same
 * volume as the YouTube audio and the same headphone route. Each cue takes transient "may duck" focus so
 * YouTube dips under the beep, and releases it right after so the video's audio comes back by itself.
 */
class CuePlayer(context: Context, private val scope: CoroutineScope) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val pcm = HashMap<CueType, ShortArray>()
    private var job: Job? = null

    /** A newer cue cuts off one still playing (cues are seconds apart, so this only happens after a stall). */
    fun play(type: CueType) {
        job?.cancel()
        job = scope.launch { playNow(type) }
    }

    fun release() {
        job?.cancel()
    }

    private suspend fun playNow(type: CueType) {
        val samples = pcm.getOrPut(type) { CueTones.render(type, SAMPLE_RATE) }
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { }
            .build()
        var track: AudioTrack? = null
        try {
            // Play even if focus is refused: a missed beep is worse than a beep over other audio.
            runCatching { audio.requestAudioFocus(focus) }
            track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(samples, 0, samples.size)
            track.play()
            delay(CueTones.durationMs(type) + TAIL_MS)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        } finally {
            track?.let { runCatching { it.stop() }; runCatching { it.release() } }
            runCatching { audio.abandonAudioFocusRequest(focus) }
        }
    }

    private companion object {
        const val SAMPLE_RATE = 44100
        const val TAIL_MS = 150L
    }
}
