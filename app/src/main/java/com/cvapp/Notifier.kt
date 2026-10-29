package com.cvapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import androidx.core.app.NotificationCompat
import org.json.JSONObject

/**
 * CV Chat's notifications, from what the house sealed for this phone.
 *
 * The house picks the channel from the message's first line, the house style
 * `<emoji> *Section — View*`. Each rings with the app's own sound until the
 * person picks another for it in Android.
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
        Triple("messages", R.string.channel_messages, NotificationManager.IMPORTANCE_DEFAULT),
        Triple("replies", R.string.channel_replies, NotificationManager.IMPORTANCE_LOW),
        Triple("alarms", R.string.channel_alarms, NotificationManager.IMPORTANCE_DEFAULT),
        Triple("system", R.string.channel_system, NotificationManager.IMPORTANCE_LOW),
        Triple("fun", R.string.channel_fun, NotificationManager.IMPORTANCE_LOW),
        Triple("connection", R.string.channel_connection, NotificationManager.IMPORTANCE_MIN),
    )

    // A channel keeps the sound it was made with, whatever the app says later.
    // So the channels made before the app had a sound of its own (2.1) give way
    // to new ones under new ids; the house names them as it always did. The
    // listening notice's channel makes no sound and stays as it was.
    private const val SOUNDED = "-s1"

    private fun channelId(name: String) = if (name == "connection") name else name + SOUNDED

    // CV's coyote in the status bar, and in the shade on a circle of the
    // person's own colour: the look the house last gave this phone, Amber
    // before it has given one.
    private fun cv(builder: NotificationCompat.Builder, ctx: Context) = builder
        .setSmallIcon(R.drawable.ic_stat_cv)
        .setColor((Look.current.takeIf { it.fromHouse } ?: Look.load(ctx)).accent)

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val sound = Uri.parse("android.resource://${ctx.packageName}/${R.raw.notify}")
        val audio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        nm.createNotificationChannels(CHANNELS.map { (name, label, importance) ->
            NotificationChannel(channelId(name), ctx.getString(label), importance).apply {
                if (name != "connection") setSound(sound, audio)
            }
        })
        CHANNELS.forEach { (name, _, _) -> if (name != "connection") nm.deleteNotificationChannel(name) }
    }

    /** The listening service's own notice: required by Android, kept as quiet as it allows. */
    fun linkNotice(ctx: Context): Notification =
        cv(NotificationCompat.Builder(ctx, "connection"), ctx)
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
        // A channel this build does not know (a newer house) would drop the
        // notification on the floor: Android posts nothing to a missing channel.
        val channel = p.optString("ch").takeIf { ch -> CHANNELS.any { it.first == ch } } ?: "household"
        val builder = cv(NotificationCompat.Builder(ctx, channelId(channel)), ctx)
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
        Intent(Intent.ACTION_VIEW, Uri.parse("${BuildConfig.LINK_SCHEME}://cv-chat/chat" +
            if (thread.isNotEmpty()) "#$thread" else ""), ctx, MainActivity::class.java)

    private fun openChat(ctx: Context, thread: String, code: Int): PendingIntent =
        PendingIntent.getActivity(ctx, code, chatIntent(ctx, thread),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}
