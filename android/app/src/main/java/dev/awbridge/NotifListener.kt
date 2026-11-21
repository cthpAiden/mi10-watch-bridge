package dev.awbridge

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

/** Reads Google Maps navigation and (optionally) other notifications and hands them to the bridge. */
class NotifListener : NotificationListenerService() {

    companion object {
        const val MAPS = "com.google.android.apps.maps"
    }

    private var navKey: String? = null
    private var lastNavLog: String? = null
    private val forwarded = HashMap<String, Int>()

    override fun onListenerConnected() {
        Logx.log("Đã có quyền đọc thông báo")
        try {
            activeNotifications?.forEach { handle(it) }
        } catch (_: Throwable) {
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            handle(sbn)
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
            service.navEnded()
        } else if (forwarded.remove(sbn.key) != null) {
            val json = JSONObject().put("id", sbn.key)
            service.send(Protocol.T_NOTIF_REMOVE, json.toString().toByteArray(Charsets.UTF_8))
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        val service = BridgeService.instance ?: return
        val n = sbn.notification ?: return

        if (sbn.packageName == MAPS && (sbn.isOngoing || n.category == Notification.CATEGORY_NAVIGATION)) {
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

        if (!Prefs.forward(this)) return
        if (sbn.packageName == packageName) return
        val isCall = n.category == Notification.CATEGORY_CALL
        if (sbn.isOngoing && !isCall) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        val hash = (title + "\u0000" + text).hashCode()
        if (forwarded[sbn.key] == hash) return
        if (forwarded.size > 200) forwarded.clear()
        forwarded[sbn.key] = hash

        val json = JSONObject()
            .put("id", sbn.key)
            .put("app", appLabel(sbn.packageName))
            .put("t", title.take(80))
            .put("x", text.take(300))
            .put("c", if (isCall) "call" else "")
        service.send(Protocol.T_NOTIF, json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) {
        pkg.substringAfterLast('.')
    }
}
