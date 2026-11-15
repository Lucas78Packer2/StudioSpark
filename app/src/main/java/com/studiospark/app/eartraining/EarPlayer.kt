package com.studiospark.app.eartraining

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/** Where rendered clips go. Mono PCM16 at [EAR_SR]. */
interface EarPlayer {
    fun play(pcm: ShortArray)
    fun stop()
}

/** Routes clips through the app's existing engine, e.g. `PreviewEarPlayer { vm.engine.preview(it) }` (AudioEngine.preview takes ENGINE_SR mono; null stops). */
class PreviewEarPlayer(private val preview: (ShortArray?) -> Unit) : EarPlayer {
    override fun play(pcm: ShortArray) = preview(pcm)
    override fun stop() = preview(null)
}

/** Standalone player for when no engine is passed in. Created lazily by the screen; released on dispose. */
class AudioTrackEarPlayer : EarPlayer {
    private var track: AudioTrack? = null
    @Synchronized override fun play(pcm: ShortArray) {
        stop()
        if (pcm.isEmpty()) return
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(EAR_SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
        t.write(pcm, 0, pcm.size); t.play(); track = t
    }
    @Synchronized override fun stop() { track?.let { runCatching { it.stop() }; it.release() }; track = null }
}
