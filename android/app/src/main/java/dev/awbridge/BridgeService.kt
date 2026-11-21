package dev.awbridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import org.json.JSONObject
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Foreground service that makes the phone a BLE peripheral for the watch app.
 * It carries navigation, notifications, the phone's position, map tiles and
 * the planned route. All state is touched on the main thread only.
 */
@SuppressLint("MissingPermission")
class BridgeService : Service() {

    companion object {
        @Volatile
        var instance: BridgeService? = null

        private const val CHANNEL = "bridge"
        private const val NOTIF_ID = 1
        private const val NAV_MIN_INTERVAL_MS = 700L

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, BridgeService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, BridgeService::class.java))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var server: BluetoothGattServer? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var advertiser: BluetoothLeAdvertiser? = null

    /** The central that subscribed to TX. Null until the watch app subscribes. */
    private var watch: BluetoothDevice? = null
    private var mtu = 23

    /** Address of the central whose link this app has claimed with [claimLink]. */
    private var heldAddress: String? = null
    private var connectedAt = 0L

    // Outgoing data. Small, time critical frames (turns, position, notifications)
    // go first; map tiles wait in their own queue so they never delay a turn.
    private val urgent = ArrayDeque<ByteArray>()
    private val bulk = ArrayDeque<ByteArray>()
    private val chunks = ArrayDeque<ByteArray>()
    private var sending = false
    private var sendStartedAt = 0L
    private var sendFailures = 0

    private val parser = FrameParser { type, payload -> onWatchFrame(type, payload) }

    // Last navigation state, replayed when the watch (re)connects.
    private var lastNav: ByteArray? = null
    private var lastIcon: ByteArray? = null
    private var pendingNav: ByteArray? = null
    private var pendingIcon: ByteArray? = null
    private var lastNavSentAt = 0L
    private var navFlushScheduled = false

    /** True while Google Maps is giving directions. Its turns win over this app's own route. */
    @Volatile
    var mapsGuiding = false
        private set

    // Position and route.
    private var locating = false
    private var mapWanted = false
    private var lastGpsAt = 0L
    private var lastFix: LatLon? = null
    private var lastLocation: Location? = null
    private var lastFixSentAt = 0L
    private var destination: LatLon? = null
    private var destinationLabel = ""
    private var progress: RouteProgress? = null
    private var routeRequestRunning = false
    private var lastRouteAt = 0L
    private var offRouteFixes = 0
    private var tapCounter = 0
    private var warnedManeuver: Maneuver? = null

    private var ringtone: Ringtone? = null
    private val stopRingRunnable = Runnable { stopRing() }

    val watchConnected: Boolean
        get() = watch != null

    /** True while a route planned in this app is being followed (or fetched). */
    val routeActive: Boolean
        get() = destination != null

    /** The most recent position fix, if the GPS has been running. */
    val lastPoint: LatLon?
        get() = lastFix

    val routeSummary: String
        get() = when {
            destination == null -> "chưa đặt"
            progress == null -> "đang tìm đường tới $destinationLabel"
            else -> "tới $destinationLabel"
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (hasLocationPermission()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(NOTIF_ID, notification, type)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(btStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(btStateReceiver, filter)
        }
        startBle()
        main.postDelayed(listenerWatch, 5000)
        // A destination set before the app was restarted (or killed by the system) is followed again.
        Prefs.savedRoute(this)?.let { (lat, lon, label) ->
            main.post {
                if (destination == null) startRoute(LatLon(lat, lon), label)
            }
        }
    }

    /** Google Maps and every other notification arrive through the listener: keep it bound. */
    private val listenerWatch = object : Runnable {
        override fun run() {
            NotifListener.ensureBound(this@BridgeService)
            main.postDelayed(this, 30_000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        try {
            unregisterReceiver(btStateReceiver)
        } catch (_: Throwable) {
        }
        stopLocation()
        stopBle()
        stopRing()
        main.removeCallbacksAndMessages(null)
        instance = null
        Logx.log("Cầu nối đã tắt")
        super.onDestroy()
    }

    // ---------------------------------------------------------------- BLE setup

    private fun hasBtPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
    }

    private fun startBle() {
        if (server != null) return
        if (!hasBtPermission()) {
            Logx.log("Thiếu quyền Bluetooth. Bấm nút 1 rồi bật lại cầu nối.")
            return
        }
        val manager = getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        if (manager == null || adapter == null || !adapter.isEnabled) {
            Logx.log("Bluetooth đang tắt. Bật Bluetooth để tiếp tục.")
            return
        }
        val srv = manager.openGattServer(this, gattCallback)
        if (srv == null) {
            Logx.log("Không mở được GATT server")
            return
        }
        val encrypted = Prefs.encrypt(this)
        val tx = BluetoothGattCharacteristic(
            Protocol.TX,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        tx.addDescriptor(
            BluetoothGattDescriptor(
                Protocol.CCCD,
                BluetoothGattDescriptor.PERMISSION_READ or
                    (if (encrypted) BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED
                    else BluetoothGattDescriptor.PERMISSION_WRITE)
            )
        )
        val rx = BluetoothGattCharacteristic(
            Protocol.RX,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            if (encrypted) BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
            else BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val service = BluetoothGattService(Protocol.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(tx)
        service.addCharacteristic(rx)
        srv.addService(service)
        server = srv
        txChar = tx
        if (encrypted) Logx.log("Kết nối có mã hóa: watch sẽ hỏi ghép đôi ở lần nối đầu")

        val adv = adapter.bluetoothLeAdvertiser
        if (adv == null) {
            Logx.log("Máy không hỗ trợ BLE advertising")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(Protocol.SERVICE))
            .build()
        adv.startAdvertising(settings, data, advertiseCallback)
        advertiser = adv
    }

    private fun stopBle() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Throwable) {
        }
        advertiser = null
        try {
            server?.close()
        } catch (_: Throwable) {
        }
        server = null
        txChar = null
        heldAddress = null
        watch = null
        clearQueues()
        parser.reset()
    }

    private fun clearQueues() {
        urgent.clear()
        bulk.clear()
        chunks.clear()
        sending = false
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Logx.log("Đang phát BLE, chờ watch kết nối")
        }

        override fun onStartFailure(errorCode: Int) {
            Logx.log("Phát BLE lỗi, mã $errorCode")
        }
    }

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> startBle()
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    stopBle()
                    updateNotification()
                }
            }
        }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            main.post {
                if (newState != BluetoothProfile.STATE_DISCONNECTED) return@post
                if (device.address == heldAddress) releaseLink(device)
                if (device.address == watch?.address) {
                    watch = null
                    clearQueues()
                    parser.reset()
                    mtu = 23
                    mapWanted = false
                    refreshLocation()
                    val seconds = (SystemClock.elapsedRealtime() - connectedAt) / 1000
                    Logx.log("Watch ngắt kết nối sau ${seconds}s (${disconnectReason(status)})")
                    updateNotification()
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            main.post { this@BridgeService.mtu = mtu }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            val value = if (device.address == watch?.address) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            if (descriptor.uuid != Protocol.CCCD) return
            val enable = value != null && value.isNotEmpty() && value[0].toInt() != 0
            main.post {
                if (enable) {
                    watch = device
                    connectedAt = SystemClock.elapsedRealtime()
                    claimLink(device)
                    clearQueues()
                    parser.reset()
                    Logx.log("Watch đã kết nối (MTU $mtu)")
                    replayState()
                } else if (device.address == watch?.address) {
                    watch = null
                }
                updateNotification()
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            if (characteristic.uuid == Protocol.RX && value != null) {
                main.post { parser.feed(value) }
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            main.post {
                sending = false
                sendFailures = 0
                pump()
            }
        }
    }

    // ------------------------------------------------------------- link holding

    /**
     * Registers this app as a user of the watch's link. A connection opened by
     * the remote side belongs to no app, so Android may close it when another
     * app or system service lets go of the same device. Claiming it keeps the
     * link up for as long as the watch stays subscribed.
     */
    private fun claimLink(device: BluetoothDevice) {
        if (device.address == heldAddress) return
        heldAddress?.let { old ->
            if (old != device.address) {
                try {
                    server?.cancelConnection(getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(old))
                } catch (_: Throwable) {
                }
            }
        }
        heldAddress = device.address
        try {
            server?.connect(device, false)
        } catch (t: Throwable) {
            Logx.log("Không giữ được kết nối: ${t.message}")
        }
    }

    private fun releaseLink(device: BluetoothDevice) {
        heldAddress = null
        try {
            server?.cancelConnection(device)
        } catch (_: Throwable) {
        }
    }

    /** HCI disconnect reasons, worded for the in-app log. */
    private fun disconnectReason(status: Int): String = when (status) {
        0 -> "bình thường"
        8 -> "mất sóng, mã 8"
        19 -> "watch chủ động ngắt, mã 19"
        22 -> "điện thoại ngắt, mã 22"
        34 -> "hết giờ phản hồi, mã 34"
        62 -> "không thiết lập được, mã 62"
        else -> "mã $status"
    }

    // ------------------------------------------------------------------ sending

    /**
     * Queues one frame for the watch. Safe to call from any thread. Dropped if no watch.
     *
     * @param bulkFrame large, low priority data (map tiles); sent only when nothing urgent waits
     * @param replaceQueued drop older queued frames of the same type (a newer position replaces an unsent one)
     */
    fun send(type: Int, payload: ByteArray, bulkFrame: Boolean = false, replaceQueued: Boolean = false) {
        main.post {
            if (watch == null) return@post
            val frame = Protocol.frame(type, payload)
            if (bulkFrame) {
                while (bulk.size >= 8) bulk.removeFirst()
                bulk.addLast(frame)
            } else {
                if (replaceQueued) urgent.removeAll { it[1] == type.toByte() }
                if (urgent.size > 120) {
                    urgent.clear()
                    Logx.log("Hàng đợi gửi đầy, đã xóa")
                }
                urgent.addLast(frame)
            }
            pump()
        }
    }

    @Suppress("DEPRECATION")
    private fun pump() {
        val now = SystemClock.elapsedRealtime()
        if (sending && now - sendStartedAt < 2000) return
        sending = false
        val device = watch
        val characteristic = txChar
        val srv = server
        if (device == null || characteristic == null || srv == null) {
            clearQueues()
            return
        }
        if (chunks.isEmpty()) {
            // A frame is never interleaved with another one: the next frame is
            // only split into packets once the previous one is fully out.
            val frame = urgent.removeFirstOrNull() ?: bulk.removeFirstOrNull() ?: return
            val size = (mtu - 3).coerceIn(20, 500)
            var offset = 0
            while (offset < frame.size) {
                val end = minOf(offset + size, frame.size)
                chunks.addLast(frame.copyOfRange(offset, end))
                offset = end
            }
        }
        val chunk = chunks.removeFirstOrNull() ?: return
        val ok = try {
            if (Build.VERSION.SDK_INT >= 33) {
                srv.notifyCharacteristicChanged(device, characteristic, false, chunk) == BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.value = chunk
                srv.notifyCharacteristicChanged(device, characteristic, false)
            }
        } catch (t: Throwable) {
            false
        }
        if (ok) {
            sending = true
            sendStartedAt = now
        } else {
            sendFailures++
            if (sendFailures > 20) {
                sendFailures = 0
                clearQueues()
                Logx.log("Gửi BLE thất bại nhiều lần, bỏ hàng đợi")
            } else {
                chunks.addFirst(chunk)
                main.postDelayed({ pump() }, 150)
            }
        }
    }

    /** Brings a watch that just (re)connected up to date. */
    private fun replayState() {
        lastIcon?.let { send(Protocol.T_ICON, it) }
        lastNav?.let { send(Protocol.T_NAV, it) }
        sendRoute()
        NotifListener.instance?.syncAll()
    }

    // --------------------------------------------------------------- navigation

    /** New navigation state (from Google Maps or from our own route). Rate limited, duplicates dropped. */
    fun updateNav(navJson: ByteArray, icon: ByteArray?) {
        main.post {
            pendingNav = navJson
            pendingIcon = icon
            val wait = NAV_MIN_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastNavSentAt)
            if (wait <= 0) {
                flushNav()
            } else if (!navFlushScheduled) {
                navFlushScheduled = true
                main.postDelayed({
                    navFlushScheduled = false
                    flushNav()
                }, wait)
            }
        }
    }

    private fun flushNav() {
        val nav = pendingNav ?: return
        val icon = pendingIcon
        pendingNav = null
        pendingIcon = null
        lastNavSentAt = SystemClock.elapsedRealtime()
        if (icon != null && !icon.contentEquals(lastIcon)) {
            lastIcon = icon
            send(Protocol.T_ICON, icon)
        }
        if (!nav.contentEquals(lastNav)) {
            lastNav = nav
            send(Protocol.T_NAV, nav)
        }
    }

    /** A turn read from the Google Maps notification. */
    fun mapsNav(navJson: ByteArray, icon: ByteArray?) {
        main.post {
            if (!mapsGuiding) {
                mapsGuiding = true
                if (destination != null) {
                    Logx.log("Google Maps đang dẫn đường: watch hiện chỉ dẫn của Google Maps")
                }
            }
        }
        updateNav(navJson, icon)
    }

    /** Google Maps stopped navigating: back to this app's own route, if one is set. */
    fun mapsNavEnded() {
        main.post {
            if (!mapsGuiding) return@post
            mapsGuiding = false
            val fix = lastFix
            if (destination != null && progress != null && fix != null) followRoute(fix, 0.0) else navEnded()
        }
    }

    fun navEnded() {
        main.post {
            pendingNav = null
            pendingIcon = null
            lastIcon = null
            val off = JSONObject().put("a", false).toString().toByteArray()
            if (!off.contentEquals(lastNav)) {
                lastNav = off
                send(Protocol.T_NAV, off)
                Logx.log("Kết thúc chỉ đường")
            }
        }
    }

    // ----------------------------------------------------------------- location

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            onLocation(location)
        }

        override fun onProviderEnabled(provider: String) {}

        override fun onProviderDisabled(provider: String) {}

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        }
    }

    /** The position is needed while the watch shows the map and while a route is followed. */
    private fun refreshLocation() {
        if ((mapWanted && watch != null) || destination != null) startLocation() else stopLocation()
    }

    private fun startLocation() {
        if (locating) return
        if (!hasLocationPermission()) {
            Logx.log("Thiếu quyền vị trí. Bấm nút 1 để cấp, rồi bật lại cầu nối.")
            return
        }
        val manager = getSystemService(LocationManager::class.java) ?: return
        var started = false
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (!manager.allProviders.contains(provider)) continue
                manager.requestLocationUpdates(provider, 1000L, 0f, locationListener, Looper.getMainLooper())
                started = true
                // Only a recent fix: an old one would put the marker (and the start
                // of a route) where the phone was hours ago.
                manager.getLastKnownLocation(provider)?.let {
                    val ageMs = (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000
                    if (ageMs in 0..60_000) onLocation(it)
                }
            } catch (t: Throwable) {
                Logx.log("Không bật được định vị $provider: ${t.message}")
            }
        }
        locating = started
        if (started) {
            Logx.log("Bắt đầu gửi vị trí lên watch")
            main.removeCallbacks(fixHeartbeat)
            main.postDelayed(fixHeartbeat, 5000)
        }
    }

    private fun stopLocation() {
        if (!locating) return
        locating = false
        main.removeCallbacks(fixHeartbeat)
        try {
            getSystemService(LocationManager::class.java)?.removeUpdates(locationListener)
        } catch (_: Throwable) {
        }
        Logx.log("Dừng gửi vị trí")
    }

    private fun onLocation(loc: Location) {
        val now = SystemClock.elapsedRealtime()
        val isGps = loc.provider == LocationManager.GPS_PROVIDER
        if (isGps) {
            lastGpsAt = now
        } else if (now - lastGpsAt < 10_000) {
            return // a fresh satellite fix beats a network estimate
        }
        val fix = LatLon(loc.latitude, loc.longitude)
        lastFix = fix
        lastLocation = loc
        sendFix(loc)
        followRoute(fix, if (loc.hasAccuracy()) loc.accuracy.toDouble() else 0.0)
    }

    /** Sends a position to the watch, with its age so the watch can tell a live fix from an old one. */
    private fun sendFix(loc: Location) {
        lastFixSentAt = SystemClock.elapsedRealtime()
        val moving = loc.hasBearing() && loc.hasSpeed() && loc.speed > 0.7f
        val ageSeconds = ((SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000_000L)
            .coerceAtLeast(0L)
        val json = JSONObject()
            .put("la", (loc.latitude * 1e6).roundToInt() / 1e6)
            .put("lo", (loc.longitude * 1e6).roundToInt() / 1e6)
            .put("b", if (moving && ageSeconds < 5) loc.bearing.roundToInt() else -1)
            .put("ac", if (loc.hasAccuracy()) loc.accuracy.roundToInt() else -1)
            .put("ag", ageSeconds)
        send(Protocol.T_LOC, json.toString().toByteArray(Charsets.UTF_8), replaceQueued = true)
    }

    /**
     * Standing still or indoors the GPS may say nothing for a long time. The
     * last position is repeated every few seconds so the watch knows the link
     * and the phone are fine, and shows how old that position is.
     */
    private val fixHeartbeat = object : Runnable {
        override fun run() {
            if (!locating) return
            val loc = lastLocation
            if (loc != null && SystemClock.elapsedRealtime() - lastFixSentAt >= 4000) sendFix(loc)
            main.postDelayed(this, 5000)
        }
    }

    // -------------------------------------------------------------------- route

    /** Plans a route from the current position to [point] and shows it on the watch. Main thread. */
    fun startRoute(point: LatLon, label: String) {
        destination = point
        destinationLabel = label
        progress = null
        offRouteFixes = 0
        warnedManeuver = null
        Prefs.saveRoute(this, point.lat, point.lon, label)
        Logx.log("Điểm đến: $label")
        refreshLocation()
        requestRoute()
        updateNotification()
    }

    /** Stops following the route. Main thread. */
    fun cancelRoute(reason: String = "Đã hủy chỉ đường") {
        if (destination == null) {
            Logx.log("Không có điểm đến nào đang đặt trong AW Bridge")
            return
        }
        destination = null
        progress = null
        Prefs.clearRoute(this)
        sendRoute()
        if (!mapsGuiding) navEnded()
        refreshLocation()
        Logx.log(reason)
        updateNotification()
    }

    private fun requestRoute() {
        val to = destination ?: return
        val from = lastFix
        if (from == null) {
            Logx.log("Đang chờ GPS để tìm đường")
            return
        }
        if (routeRequestRunning) return
        routeRequestRunning = true
        lastRouteAt = SystemClock.elapsedRealtime()
        Thread {
            val result = try {
                Router.fetch(from, to)
            } catch (t: Throwable) {
                main.post {
                    routeRequestRunning = false
                    val cause = Net.problem(this) ?: t.message
                    Logx.log("Không tìm được đường: $cause. ${Net.advice(this)}")
                }
                return@Thread
            }
            main.post {
                routeRequestRunning = false
                // The user may have cancelled or picked another place meanwhile.
                if (destination !== to) return@post
                progress = RouteProgress(result)
                offRouteFixes = 0
                warnedManeuver = null
                Logx.log("Đã có đường: ${Geo.metres(result.length)}, ${Geo.duration(result.seconds)}")
                sendRoute()
                lastFix?.let { followRoute(it, 0.0) }
            }
        }.start()
    }

    /** Sends the route line (or its removal) to the watch. */
    private fun sendRoute() {
        val route = progress?.route
        val json = JSONObject()
        if (route == null) {
            json.put("p", "")
        } else {
            json.put("p", route.encoded).put("la", route.destination.lat).put("lo", route.destination.lon)
        }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > 60_000) {
            Logx.log("Tuyến đường quá dài để vẽ trên watch, chỉ gửi chỉ dẫn rẽ")
            return
        }
        send(Protocol.T_ROUTE, bytes, replaceQueued = true)
    }

    private fun followRoute(fix: LatLon, accuracy: Double) {
        if (destination == null) return
        val tracker = progress
        if (tracker == null) {
            // Still waiting for the first position, or the last request failed: try again, not too often.
            if (!routeRequestRunning && SystemClock.elapsedRealtime() - lastRouteAt > 8000) requestRoute()
            return
        }
        val state = tracker.update(fix)
        if (state.remaining < 30 && state.offRoute < 60) {
            cancelRoute("Đã tới nơi: $destinationLabel")
            return
        }
        // Off the line for a few fixes in a row: plan again from here.
        if (state.offRoute > 50 + accuracy) {
            offRouteFixes++
            if (offRouteFixes >= 4 && SystemClock.elapsedRealtime() - lastRouteAt > 15_000) {
                Logx.log("Lệch tuyến ${state.offRoute.roundToInt()} m, tìm đường lại")
                offRouteFixes = 0
                requestRoute()
            }
        } else {
            offRouteFixes = 0
        }
        val next = state.next
        // One extra tap on the wrist shortly before each turn.
        if (next != null && state.toTurn < 70 && warnedManeuver !== next) {
            warnedManeuver = next
            tapCounter++
        }
        // Google Maps is navigating too: the line stays on the map, the turns are Google's.
        if (mapsGuiding) return
        val total = tracker.route.length
        val secondsLeft = if (total > 0) tracker.route.seconds * state.remaining / total else 0.0
        val json = JSONObject()
            .put("a", true)
            .put("d", Geo.metres(state.toTurn))
            .put("i", next?.instruction ?: "Tới $destinationLabel")
            .put("e", "${Geo.duration(secondsLeft)} · ${Geo.metres(state.remaining)}")
            .put("v", tapCounter)
        updateNav(json.toString().toByteArray(Charsets.UTF_8), ManeuverIcon.bits(next?.type ?: 4))
    }

    // ------------------------------------------------------------ from the watch

    private fun onWatchFrame(type: Int, payload: ByteArray) {
        val text = String(payload, Charsets.UTF_8)
        val json = try {
            JSONObject(text)
        } catch (_: Throwable) {
            JSONObject()
        }
        when (type) {
            Protocol.T_HELLO -> Logx.log("Watch chào: $text")
            Protocol.T_ACTION -> when (json.optString("a")) {
                "ring" -> ring()
                "stopring" -> stopRing()
                "stoproute" -> cancelRoute()
                else -> Logx.log("Lệnh lạ từ watch: $text")
            }
            Protocol.T_MAP -> {
                mapWanted = json.optBoolean("on")
                refreshLocation()
            }
            Protocol.T_TILE_REQ -> sendTile(json.optInt("z", -1), json.optInt("x", -1), json.optInt("y", -1))
            Protocol.T_NOTIF_DISMISS -> NotifListener.instance?.dismiss(json.optString("id"))
            else -> Logx.log("Gói lạ từ watch: loại $type, ${payload.size} byte")
        }
    }

    private fun sendTile(z: Int, x: Int, y: Int) {
        if (z !in 1..19) return
        val limit = 1 shl z
        if (x !in 0 until limit || y !in 0 until limit) return
        Tiles.fetch(applicationContext, z, x, y) { bytes ->
            val header = ByteBuffer.allocate(10)
                .put(z.toByte())
                .putInt(x)
                .putInt(y)
                .put((if (bytes == null) 1 else 0).toByte())
                .array()
            if (bytes == null) {
                send(Protocol.T_TILE, header)
            } else {
                send(Protocol.T_TILE, header + bytes, bulkFrame = true)
            }
        }
    }

    private fun ring() {
        stopRing()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val r = RingtoneManager.getRingtone(this, uri) ?: return
        r.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        r.play()
        ringtone = r
        main.postDelayed(stopRingRunnable, 20_000)
        Logx.log("Watch yêu cầu đổ chuông tìm máy")
    }

    private fun stopRing() {
        main.removeCallbacks(stopRingRunnable)
        try {
            ringtone?.stop()
        } catch (_: Throwable) {
        }
        ringtone = null
    }

    // ------------------------------------------------------------- notification

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Cầu nối Apple Watch", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val state = if (watch != null) "Đã kết nối Apple Watch" else "Đang chờ Apple Watch"
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("AW Bridge")
            .setContentText(if (destination != null) "$state · chỉ đường $routeSummary" else state)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        } catch (_: Throwable) {
        }
    }
}
