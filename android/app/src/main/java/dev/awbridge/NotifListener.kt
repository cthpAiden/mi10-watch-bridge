package dev.awbridge

import android.app.Notification
import android.app.NotificationManager
import android.os.Bundle
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
    }

    private var navKey: String? = null
    private var lastNavLog: String? = null

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
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
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
            navKey = null
            lastNavLog = null
            if (!service.routeActive) service.navEnded()
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
            // A route planned in this app drives the watch itself; Google Maps would fight it.
            if (service.routeActive) return
            val info = NavParser.parse(this, n)
            if (info != null) {
                navKey = sbn.key
                service.updateNav(info.json(), info.icon)
                val key = info.instruction
                if (key != lastNavLog) {
                    lastNavLog = key
                    Logx.log("Maps: ${info.key()}" + if (info.icon == null) " (không có icon)" else "")
                }
            }
            return
        }

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
