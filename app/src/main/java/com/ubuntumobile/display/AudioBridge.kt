package com.ubuntumobile.display

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Plays the container's sound on the phone.
 *
 * PulseAudio inside the container plays into a virtual sound card and
 * streams it as raw PCM on 127.0.0.1:4713 (see linx-audio.sh). This reads
 * that stream and writes it to an [AudioTrack], so Linux applications play
 * through the phone's speaker, headphones or Bluetooth.
 *
 * The stream never stops: when nothing plays it carries silence. Silence
 * is not passed on, and after a moment the track is paused, so an idle
 * desktop does not keep the phone's audio hardware awake.
 */
class AudioBridge(private val port: Int = 4713) {

    companion object {
        private const val TAG = "AudioBridge"
        const val SAMPLE_RATE = 48_000
        private const val FRAME_BYTES = 4 // 16-bit stereo

        /** Pause the track after this much silence. */
        private const val PAUSE_AFTER_BYTES = SAMPLE_RATE * FRAME_BYTES
    }

    @Volatile
    private var running = false
    private var thread: Thread? = null

    @Volatile
    private var socket: Socket? = null

    /** Start playing, connecting (and reconnecting) to the stream on its own. */
    @Synchronized
    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "audio-bridge").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        thread = null
    }

    private fun loop() {
        while (running) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", port), 2000)
                    s.tcpNoDelay = true
                    socket = s
                    Log.i(TAG, "playing the desktop's sound")
                    play(s.getInputStream())
                }
            } catch (_: Exception) {
                // The sound server is not up (yet); try again shortly.
            }
            socket = null
            if (running) Thread.sleep(3000)
        }
    }

    private fun play(input: InputStream) {
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            // About a tenth of a second: enough to ride out a busy moment,
            // short enough that sound stays in step with the picture.
            .setBufferSizeInBytes(maxOf(minBuffer * 2, SAMPLE_RATE * FRAME_BYTES / 10))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            val buf = ByteArray(4096)
            var carry = 0
            var silentBytes = 0
            var playing = false
            while (running) {
                val n = input.read(buf, carry, buf.size - carry)
                if (n < 0) break
                val total = carry + n
                // Only whole frames go to the track.
                val usable = total - total % FRAME_BYTES
                if (isSilent(buf, usable)) {
                    silentBytes += usable
                    if (playing && silentBytes > PAUSE_AFTER_BYTES) {
                        track.pause()
                        track.flush()
                        playing = false
                    }
                } else {
                    silentBytes = 0
                    if (!playing) {
                        track.play()
                        playing = true
                    }
                    track.write(buf, 0, usable)
                }
                carry = total - usable
                if (carry > 0) System.arraycopy(buf, usable, buf, 0, carry)
            }
        } finally {
            try {
                track.stop()
            } catch (_: Exception) {
            }
            track.release()
        }
    }

    private fun isSilent(buf: ByteArray, length: Int): Boolean {
        for (i in 0 until length) if (buf[i].toInt() != 0) return false
        return true
    }
}
