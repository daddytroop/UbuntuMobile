package com.ubuntumobile.display

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The phone's microphone, for Linux apps (calls, voice messages, recording).
 *
 * PulseAudio in the container has a "Phone microphone" input fed from
 * 127.0.0.1:4714 (linx-audio.sh), and linx-mic.sh writes 1 to
 * /tmp/linx-mic-wanted while a Linux app records from it. This watches that
 * file and only then records from the microphone and sends the sound
 * (48 kHz, mono, 16-bit), so the microphone is off whenever nothing in
 * Linux is listening.
 */
class MicBridge(
    private val context: Context,
    private val wantedFile: File,
    private val port: Int = 4714,
) {
    companion object {
        private const val TAG = "MicBridge"
        private const val SAMPLE_RATE = 48_000
    }

    /** Called (on the bridge's thread) when Linux wants the microphone but the app may not use it. */
    @Volatile var onNeedPermission: () -> Unit = {}

    @Volatile private var running = false
    private var thread: Thread? = null

    @Synchronized
    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "mic-bridge").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        thread = null
    }

    private fun wanted(): Boolean = try {
        wantedFile.readText().trim() == "1"
    } catch (_: Exception) {
        false
    }

    private fun allowed() =
        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun loop() {
        var asked = false
        while (running) {
            if (!wanted()) {
                asked = false
                Thread.sleep(500)
                continue
            }
            if (!allowed()) {
                if (!asked) {
                    asked = true
                    onNeedPermission()
                }
                Thread.sleep(1000)
                continue
            }
            try {
                record()
            } catch (e: Exception) {
                Log.w(TAG, "microphone stopped", e)
                Thread.sleep(1000)
            }
        }
    }

    /** Send the microphone to Linux until no Linux app is listening. */
    @SuppressLint("MissingPermission") // checked by allowed()
    private fun record() {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(
            // Tuned for calls: echo cancelling and noise suppression where the phone has them.
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, SAMPLE_RATE / 5 * 2),
        )
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 3000)
            socket.tcpNoDelay = true
            val out = socket.getOutputStream()
            val buffer = ByteArray(SAMPLE_RATE / 50 * 2) // 20 ms
            recorder.startRecording()
            Log.i(TAG, "microphone on for Linux")
            try {
                var checked = System.currentTimeMillis()
                while (running) {
                    val n = recorder.read(buffer, 0, buffer.size)
                    if (n > 0) out.write(buffer, 0, n)
                    if (System.currentTimeMillis() - checked > 500) {
                        checked = System.currentTimeMillis()
                        if (!wanted()) break
                    }
                }
            } finally {
                recorder.stop()
                recorder.release()
                Log.i(TAG, "microphone off")
            }
        }
    }
}
