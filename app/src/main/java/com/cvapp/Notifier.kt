package com.cvapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import org.json.JSONObject

/**
 * CV Chat's notifications, from what the house sealed for this phone.
 *
 * The house picks the channel from the message's first line, the house style
 * `<emoji> *Section — View*`; the person sets each channel's sound in Android.
 * A message that answers something the person just did comes on the quiet
 * channel. Repeats of one panel replace each other (question 89), and a read on
 * another device clears them here (question 90).
 */
object Notifier {

    const val LINK_ID = 1
    private const val MESSAGE_ID = 7
    const val EXTRA_TAP = "cv_tap"

    // What the person is looking at: the chat page reports its thread through
    // the bridge (empty when it shows none), the activity whether it is on
    // screen at all. A message for that very thread is already in front of
    // them, so it does not ring as well.
    @Volatile var onScreen: String = ""
    @Volatile var appVisible: Boolean = false

    private val CHANNELS = listOf(
        Triple("security", R.string.channel_security, NotificationManager.IMPORTANCE_HIGH),
        Triple("mail", R.string.channel_mail, NotificationManager.IMPORTANCE_DEFAULT),
        Triple("household", R.string.channel_household, NotificationManager.IMPORTANCE_DEFAULT),
        Triple("replies", R.string.channel_replies, NotificationManager.IMPORTANCE_LOW),
        Triple("alarms", R.string.channel_alarms, NotificationManager.IMPORTANCE_DEFAULT),
        Triple("system", R.string.channel_system, NotificationManager.IMPORTANCE_LOW),
        Triple("fun", R.string.channel_fun, NotificationManager.IMPORTANCE_LOW),
        Triple("connection", R.string.channel_connection, NotificationManager.IMPORTANCE_MIN),
    )

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(CHANNELS.map { (id, name, importance) ->
            NotificationChannel(id, ctx.getString(name), importance)
        })
    }

    /** The listening service's own notice: required by Android, kept as quiet as it allows. */
    fun linkNotice(ctx: Context): Notification =
        NotificationCompat.Builder(ctx, "connection")
            .setSmallIcon(R.drawable.ic_mdi_message_text_outline)
            .setContentTitle(ctx.getString(R.string.push_listening))
            .setContentIntent(openChat(ctx, "", 0))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    fun handle(ctx: Context, p: JSONObject) {
        when (p.optString("k")) {
            "read" -> clear(ctx, p.optString("t"), p.optLong("u"))
            "msg", "edit" -> post(ctx, p)
        }
    }

    private fun post(ctx: Context, p: JSONObject) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val thread = p.optString("t")
        if (appVisible && thread.isNotEmpty() && thread == onScreen) return
        val fold = p.optString("f")
        // A message whose header names a panel replaces that panel's last
        // notification; anything else stands on its own.
        val tag = if (fold.endsWith(":|") || fold.isEmpty()) "cv:$thread:${p.optLong("m")}" else "cv:$fold"
        val edit = p.optString("k") == "edit"
        if (edit && nm.activeNotifications.none { it.tag == tag }) return
        val body = p.optString("b")
        val first = body.substringBefore('\n')
        val builder = NotificationCompat.Builder(ctx, p.optString("ch").ifEmpty { "household" })
            .setSmallIcon(R.drawable.ic_mdi_message_text_outline)
            .setContentTitle(p.optString("ti"))
            .setContentText(first)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setWhen(p.optLong("d") * 1000L)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setGroup("cv:$thread")
            .setSilent(p.optBoolean("s") || edit)
            .setOnlyAlertOnce(edit)
            .setContentIntent(openChat(ctx, thread, tag.hashCode()))
        // The message's own buttons, while they still work (question 86). A tap
        // opens the chat and presses the button there, as the person.
        val expires = p.optLong("x")
        if (expires == 0L || expires * 1000L > System.currentTimeMillis()) {
            val buttons = p.optJSONArray("bt")
            for (i in 0 until minOf(buttons?.length() ?: 0, 3)) {
                val b = buttons!!.optJSONArray(i) ?: continue
                val tap = JSONObject().put("t", thread).put("m", p.optLong("m")).put("p", b.optString(1)).put("tag", tag)
                val intent = chatIntent(ctx, thread).putExtra(EXTRA_TAP, tap.toString())
                val pending = PendingIntent.getActivity(ctx, (tag + i).hashCode(), intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                builder.addAction(0, b.optString(0), pending)
            }
        }
        if (expires > 0L) builder.setTimeoutAfter(maxOf(expires * 1000L - System.currentTimeMillis(), 60_000L))
        nm.notify(tag, MESSAGE_ID, builder.build())
    }

    /** Read on another device: clear what was read here too. */
    private fun clear(ctx: Context, thread: String, upto: Long) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.activeNotifications
            .filter { it.notification.group == "cv:$thread" && it.notification.`when` <= upto * 1000L }
            .forEach { nm.cancel(it.tag, it.id) }
    }

    fun cancel(ctx: Context, tag: String) =
        ctx.getSystemService(NotificationManager::class.java).cancel(tag, MESSAGE_ID)

    private fun chatIntent(ctx: Context, thread: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("${BuildConfig.LINK_SCHEME}://cv-panel/chat" +
            if (thread.isNotEmpty()) "#$thread" else ""), ctx, MainActivity::class.java)

    private fun openChat(ctx: Context, thread: String, code: Int): PendingIntent =
        PendingIntent.getActivity(ctx, code, chatIntent(ctx, thread),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}
