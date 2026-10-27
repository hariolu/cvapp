package com.cvapp

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import org.json.JSONObject
import java.io.File

/**
 * A voice note for CV Chat, recorded by the app itself: the chat page opens the
 * house on plain http, where a page gets no microphone of its own. Opus in Ogg,
 * as Telegram's own voice notes, from Android 10; AAC in MP4 before that, which
 * the house turns into Opus.
 *
 * The page asks for it through the bridge, on the bridge's own thread, so every
 * call holds the lock.
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var mime = "audio/ogg"

    val recording: Boolean
        @Synchronized get() = recorder != null

    /** "" once it records, else why not ("busy", "failed"). */
    @Synchronized
    fun start(): String {
        if (recorder != null) return "busy"
        val ogg = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val out = File(context.cacheDir, "voice-${System.currentTimeMillis()}" + if (ogg) ".ogg" else ".m4a")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
                else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            if (ogg) {
                r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            } else {
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            }
            r.setAudioChannels(1)
            r.setAudioSamplingRate(48000)
            r.setAudioEncodingBitRate(32000)
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = out
            mime = if (ogg) "audio/ogg" else "audio/mp4"
            ""
        } catch (e: Exception) {
            r.release()
            out.delete()
            "failed"
        }
    }

    /** How loud it is now, 0–32767: the page draws the recording's level from it. */
    @Synchronized
    fun level(): Int = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)

    /** The recording for the page as {b64, mime}, or "" when dropped or empty. */
    @Synchronized
    fun stop(keep: Boolean): String {
        val r = recorder ?: return ""
        val f = file
        recorder = null
        file = null
        // stop() throws when nothing was recorded yet (a tap and straight away a second).
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        if (f == null) return ""
        return try {
            if (!keep || !ok || f.length() == 0L) ""
            else JSONObject()
                .put("b64", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
                .put("mime", mime)
                .toString()
        } finally {
            f.delete()
        }
    }
}
