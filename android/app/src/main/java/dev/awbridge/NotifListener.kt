package dev.awbridge

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

/**
 * Reads Google Maps navigation and mirrors every other notification to the watch.
 *
 * The watch keeps the same list the phone shows: new and updated notifications
 * are pushed as they arrive, removals are pushed too, and the whole list is
 * sent again whenever the watch reconnects so nothing is lost while it was away.
 */
class NotifListener : NotificationListenerService() {

    companion object {
        const val MAPS = "com.google.android.apps.maps"

        @Volatile
        var instance: NotifListener? = null

        private var lastRebindAt = 0L
        private var rebindTries = 0

        /** True when the user has switched on notification access for this app. */
        fun granted(ctx: Context): Boolean {
            val enabled = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: ""
            return enabled.contains(ctx.packageName)
        }

        /**
         * After an app update Android can leave the listener unbound although
         * access is still granted: nothing is read, and nothing says so. Switching
         * the component off and on makes the system bind it again.
         */
        fun ensureBound(ctx: Context) {
            if (instance != null) {
                rebindTries = 0
                return
            }
            if (!granted(ctx) || rebindTries >= 3) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastRebindAt < 20_000) return
            lastRebindAt = now
            rebindTries++
            val component = ComponentName(ctx, NotifListener::class.java)
            try {
                // Before Android 9 a disabled listener also loses its access, so only ask there.
                if (Build.VERSION.SDK_INT >= 28) {
                    val pm = ctx.packageManager
                    pm.setComponentEnabledSetting(
                        component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
                    )
                    pm.setComponentEnabledSetting(
                        component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
                    )
                }
                NotificationListenerService.requestRebind(component)
                Logx.log("Quyền đọc thông báo đã cấp nhưng chưa chạy: đang nối lại (lần $rebindTries)")
            } catch (t: Throwable) {
                Logx.log("Không nối lại được quyền đọc thông báo: ${t.message}")
            }
        }
    }

    private var navKey: String? = null
    private var lastNavLog: String? = null
    private var warnedUnreadable = false

    /** Key and content hash of everything currently mirrored on the watch. */
    private val forwarded = HashMap<String, Int>()
    private var warnedNoEncryption = false

    override fun onListenerConnected() {
        instance = this
        Logx.log("Đã có quyền đọc thông báo")
        syncAll()
    }

    override fun onListenerDisconnected() {
        instance = null
        mapsGone()
        Logx.log("Mất quyền đọc thông báo, đang xin nối lại")
        try {
            NotificationListenerService.requestRebind(ComponentName(this, NotifListener::class.java))
        } catch (_: Throwable) {
        }
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** Google Maps stopped navigating, or can no longer be read. */
    private fun mapsGone() {
        if (navKey == null) return
        navKey = null
        lastNavLog = null
        warnedUnreadable = false
        BridgeService.instance?.mapsNavEnded()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            handle(sbn, sync = false)
        } catch (t: Throwable) {
            Logx.log("Lỗi đọc thông báo: $t")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val service = BridgeService.instance ?: return
        if (sbn.key == navKey) {
            mapsGone()
        } else if (forwarded.remove(sbn.key) != null) {
            val json = JSONObject().put("id", sbn.key)
            service.send(Protocol.T_NOTIF_REMOVE, json.toString().toByteArray(Charsets.UTF_8))
        }
    }

    /** Sends the phone's whole current list. Called when the watch (re)connects. */
    fun syncAll() {
        val service = BridgeService.instance ?: return
        forwarded.clear()
        service.send(Protocol.T_NOTIF_CLEAR, ByteArray(0))
        val active = try {
            activeNotifications
        } catch (_: Throwable) {
            null
        } ?: return
        // Navigation that ended while nobody was told (Maps closed, listener restarted).
        if (navKey != null && active.none { it.key == navKey }) mapsGone()
        for (sbn in active.sortedBy { it.postTime }) {
            try {
                handle(sbn, sync = true)
            } catch (_: Throwable) {
            }
        }
    }

    /** The watch dismissed a notification: dismiss it on the phone too. */
    fun dismiss(key: String) {
        try {
            cancelNotification(key)
        } catch (_: Throwable) {
        }
    }

    private fun canForward(): Boolean {
        if (!Prefs.forward(this)) return false
        if (!Prefs.encrypt(this)) {
            // Notification text is private. It only goes out over an encrypted, paired link.
            if (!warnedNoEncryption) {
                warnedNoEncryption = true
                Logx.log("Chưa chuyển tiếp thông báo: cần bật \"Mã hóa kết nối\"")
            }
            return false
        }
        return true
    }

    private fun handle(sbn: StatusBarNotification, sync: Boolean) {
        val service = BridgeService.instance ?: return
        val n = sbn.notification ?: return

        if (sbn.packageName == MAPS && (sbn.isOngoing || n.category == Notification.CATEGORY_NAVIGATION)) {
            // Pressing Start in Google Maps always reaches the watch. A route planned
            // in this app keeps its line on the map, but the turns shown are Google's.
            val info = NavParser.parse(this, n)
            if (info != null) {
                navKey = sbn.key
                service.mapsNav(info.json(), info.icon, info.eta)
                val key = info.instruction
                if (key != lastNavLog) {
                    lastNavLog = key
                    Logx.log("Maps: ${info.key()}" + if (info.icon == null) " (không có icon)" else "")
                }
            } else if (!warnedUnreadable) {
                warnedUnreadable = true
                Logx.log("Maps: có thông báo dẫn đường nhưng không đọc được nội dung")
            }
            return
        }
        // Google Maps' other notifications (traffic, "rate this place") are not
        // worth a tap on the wrist, least of all while riding.
        if (sbn.packageName == MAPS) return

        if (!canForward()) return
        if (sbn.packageName == packageName) return
        val isCall = n.category == Notification.CATEGORY_CALL
        // Ongoing entries (music, downloads, VPN) are status, not alerts. Calls are the exception.
        if (sbn.isOngoing && !isCall) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = n.extras ?: return
        val title = (extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG))?.toString()?.trim().orEmpty()
        val text = bodyText(extras)
        if (title.isEmpty() && text.isEmpty()) return

        val hash = (title + "\u0000" + text).hashCode()
        val known = forwarded[sbn.key]
        if (known == hash) return
        if (forwarded.size > 300) forwarded.clear()
        forwarded[sbn.key] = hash

        // Tap the wrist only when the phone itself would make a sound or vibrate.
        val alertOnce = n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0
        val quiet = sync || isSilent(sbn) || (known != null && alertOnce)

        val json = JSONObject()
            .put("id", sbn.key)
            .put("app", appLabel(sbn.packageName))
            .put("t", title.take(100))
            .put("x", text.take(600))
            .put("c", if (isCall) "call" else "")
            .put("w", sbn.postTime)
            .put("q", quiet)
        service.send(Protocol.T_NOTIF, json.toString().toByteArray(Charsets.UTF_8))
    }

    /** The text the phone shows when the notification is expanded. */
    @Suppress("DEPRECATION")
    private fun bodyText(extras: Bundle): String {
        // Chat apps: the last few messages of the conversation.
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (messages != null && messages.isNotEmpty()) {
            val group = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
            val lines = ArrayList<String>()
            for (item in messages.takeLast(5)) {
                val bundle = item as? Bundle ?: continue
                val line = bundle.getCharSequence("text")?.toString()?.trim().orEmpty()
                if (line.isEmpty()) continue
                val sender = bundle.getCharSequence("sender")?.toString()?.trim().orEmpty()
                lines.add(if (group && sender.isNotEmpty()) "$sender: $line" else line)
            }
            if (lines.isNotEmpty()) return lines.joinToString("\n")
        }
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        if (big.isNotEmpty()) return big
        val inbox = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (inbox != null && inbox.isNotEmpty()) {
            return inbox.joinToString("\n") { it.toString().trim() }
        }
        return extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
    }

    /** True when the phone shows this one silently (low importance or blocked by Do Not Disturb). */
    private fun isSilent(sbn: StatusBarNotification): Boolean = try {
        val ranking = Ranking()
        if (currentRanking.getRanking(sbn.key, ranking)) {
            ranking.importance < NotificationManager.IMPORTANCE_DEFAULT || !ranking.matchesInterruptionFilter()
        } else {
            false
        }
    } catch (_: Throwable) {
        false
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) {
        pkg.substringAfterLast('.')
    }
}
