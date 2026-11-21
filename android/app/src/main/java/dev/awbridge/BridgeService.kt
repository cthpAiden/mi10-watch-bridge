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
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import org.json.JSONObject

/**
 * Foreground service that makes the phone a BLE peripheral for the watch app.
 * All state is touched on the main thread only.
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

    private var ringtone: Ringtone? = null
    private val stopRingRunnable = Runnable { stopRing() }

    val watchConnected: Boolean
        get() = watch != null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        try {
            unregisterReceiver(btStateReceiver)
        } catch (_: Throwable) {
        }
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
        watch = null
        chunks.clear()
        sending = false
        parser.reset()
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
                if (newState == BluetoothProfile.STATE_DISCONNECTED && device.address == watch?.address) {
                    watch = null
                    chunks.clear()
                    sending = false
                    parser.reset()
                    mtu = 23
                    Logx.log("Watch ngắt kết nối")
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
                    chunks.clear()
                    sending = false
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

    // ------------------------------------------------------------------ sending

    /** Queue one frame for the watch. Safe to call from any thread. Dropped if no watch. */
    fun send(type: Int, payload: ByteArray) {
        main.post {
            if (watch == null) return@post
            if (chunks.size > 400) {
                chunks.clear()
                Logx.log("Hàng đợi gửi đầy, đã xóa")
            }
            val frame = Protocol.frame(type, payload)
            val size = (mtu - 3).coerceIn(20, 500)
            var offset = 0
            while (offset < frame.size) {
                val end = minOf(offset + size, frame.size)
                chunks.addLast(frame.copyOfRange(offset, end))
                offset = end
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
            chunks.clear()
            return
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
                chunks.clear()
                Logx.log("Gửi BLE thất bại nhiều lần, bỏ hàng đợi")
            } else {
                chunks.addFirst(chunk)
                main.postDelayed({ pump() }, 150)
            }
        }
    }

    private fun replayState() {
        lastIcon?.let { send(Protocol.T_ICON, it) }
        lastNav?.let { send(Protocol.T_NAV, it) }
    }

    // --------------------------------------------------------------- navigation

    /** New navigation state from Google Maps. Rate limited, duplicates dropped. */
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

    // ------------------------------------------------------------ from the watch

    private fun onWatchFrame(type: Int, payload: ByteArray) {
        val text = String(payload, Charsets.UTF_8)
        when (type) {
            Protocol.T_HELLO -> Logx.log("Watch chào: $text")
            Protocol.T_ACTION -> {
                val action = try {
                    JSONObject(text).optString("a")
                } catch (_: Throwable) {
                    ""
                }
                when (action) {
                    "ring" -> ring()
                    "stopring" -> stopRing()
                    else -> Logx.log("Lệnh lạ từ watch: $text")
                }
            }
            else -> Logx.log("Gói lạ từ watch: loại $type, ${payload.size} byte")
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
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("AW Bridge")
            .setContentText(if (watch != null) "Đã kết nối Apple Watch" else "Đang chờ Apple Watch")
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
