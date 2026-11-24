package dev.awbridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.net.Uri
import android.location.LocationManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var destinationField: EditText
    private var testStep = 0
    private var lastLog = ""

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        status = TextView(this).apply {
            textSize = 16f
            setPadding(0, 0, 0, pad)
        }
        column.addView(status)

        column.addView(button("1. Cấp quyền Bluetooth và vị trí") { askPermissions() })
        column.addView(button("2. Cho phép đọc thông báo") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        column.addView(button("3. Bật cầu nối") { BridgeService.start(this) })
        column.addView(button("4. Cho app chạy nền (tắt tối ưu pin)") { askBackground() })
        column.addView(button("5. MIUI: Tiết kiệm pin > Không hạn chế") { openMiuiBattery() })
        column.addView(button("Tắt cầu nối") { BridgeService.stop(this) })
        column.addView(button("Gửi thử chỉ đường lên watch") { sendTest() })

        // Destination for the route drawn on the watch map. Sharing a place from
        // Google Maps to this app fills it in automatically.
        destinationField = EditText(this).apply {
            hint = "Điểm đến: tên, địa chỉ, link Google Maps hoặc 10.77, 106.69"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            textSize = 14f
        }
        column.addView(destinationField)
        column.addView(button("Chỉ đường tới điểm này (hiện trên watch)") {
            planRoute(destinationField.text.toString())
        })
        column.addView(button("Hủy chỉ đường") { BridgeService.instance?.cancelRoute() })

        column.addView(check("Chuyển tiếp thông báo các app khác", Prefs.forward(this)) {
            Prefs.setForward(this, it)
        })
        column.addView(check("Mã hóa kết nối (watch sẽ hỏi ghép đôi Bluetooth)", Prefs.encrypt(this)) {
            Prefs.setEncrypt(this, it)
            if (BridgeService.instance != null) {
                BridgeService.stop(this)
                ui.postDelayed({ BridgeService.start(this) }, 500)
            }
        })

        logView = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(0, pad, 0, 0)
        }
        column.addView(logView)

        setContentView(ScrollView(this).apply { addView(column) })
        // A rebuilt screen (rotation, reopened from recents after an update) carries the
        // old share again: only a fresh launch may set a destination.
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) handleIntent(intent)
    }

    /** A place shared from Google Maps (or any geo: link) becomes the destination. */
    private fun handleIntent(intent: Intent) {
        val shared = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        if (shared.isNullOrBlank()) return
        // Handle each share once, also when the screen is rebuilt.
        intent.action = Intent.ACTION_MAIN
        destinationField.setText(shared.lines().firstOrNull { it.isNotBlank() } ?: shared)
        planRoute(shared)
    }

    private fun planRoute(input: String) {
        if (input.isBlank()) {
            Logx.log("Chưa nhập điểm đến")
            return
        }
        if (BridgeService.instance == null) BridgeService.start(this)
        Logx.log("Đang tìm điểm đến...")
        val near = nearby()
        Thread {
            val found = try {
                Destination.resolve(input, near)
            } catch (t: Throwable) {
                null
            }
            ui.post {
                if (found == null) {
                    Logx.log("Không tìm thấy điểm đến. Thả ghim trong Google Maps rồi chia sẻ ghim đó.")
                } else {
                    startRouteWhenReady(found, 0)
                }
            }
        }.start()
    }

    /** Roughly where the phone is, so a place name is matched to the nearest place with that name. */
    @SuppressLint("MissingPermission")
    private fun nearby(): LatLon? {
        BridgeService.instance?.lastPoint?.let { return it }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val manager = getSystemService(LocationManager::class.java) ?: return null
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                manager.getLastKnownLocation(provider)?.let { return LatLon(it.latitude, it.longitude) }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    /** The service may still be starting when the destination arrives. */
    private fun startRouteWhenReady(found: Destination.Result, attempt: Int) {
        val service = BridgeService.instance
        if (service != null) {
            service.startRoute(found.point, found.label)
            openGoogleNavigation(found.point)
        } else if (attempt < 10) {
            ui.postDelayed({ startRouteWhenReady(found, attempt + 1) }, 500)
        } else {
            Logx.log("Chưa bật được cầu nối. Bấm nút 3 rồi thử lại.")
        }
    }

    /**
     * Starts Google Maps' own navigation (two-wheeler) to the same place, so one
     * share gives both Google's voice and turns and this app's route line on the watch.
     */
    private fun openGoogleNavigation(point: LatLon) {
        val uri = Uri.parse("google.navigation:q=${point.lat},${point.lon}&mode=l")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(NotifListener.MAPS))
            Logx.log("Đã mở Google Maps dẫn đường tới điểm đến")
        } catch (_: Throwable) {
            Logx.log("Không mở được Google Maps. Tự bấm Bắt đầu trong Google Maps.")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // The service declares what it may do when it starts, so a running one
        // has to restart before it can use a permission that was granted just now.
        if (BridgeService.instance != null) {
            BridgeService.stop(this)
            ui.postDelayed({ BridgeService.start(this) }, 500)
        }
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresher)
    }

    override fun onPause() {
        ui.removeCallbacks(refresher)
        super.onPause()
    }

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun check(label: String, initial: Boolean, onChange: (Boolean) -> Unit): CheckBox =
        CheckBox(this).apply {
            text = label
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }

    private fun askPermissions() {
        val wanted = ArrayList<String>()
        wanted.add(Manifest.permission.ACCESS_FINE_LOCATION)
        wanted.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            wanted.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            wanted.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissions(wanted.toTypedArray(), 1)
    }

    /**
     * With the phone in a pocket the app runs in the background, where Android
     * and MIUI cut its network unless it is exempt from battery optimisation.
     * Without that the watch gets no map tiles and no new route.
     */
    @SuppressLint("BatteryLife")
    private fun askBackground() {
        if (Net.unrestricted(this)) {
            Logx.log("Tối ưu pin đã tắt cho AW Bridge. Bấm tiếp nút 5.")
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (_: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Throwable) {
                Logx.log("Không mở được màn hình tối ưu pin. Vào Cài đặt > Ứng dụng > AW Bridge > Tiết kiệm pin.")
            }
        }
    }

    /** MIUI keeps its own per-app battery rule, separate from Android's. */
    private fun openMiuiBattery() {
        val miui = Intent()
            .setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
            .putExtra("package_name", packageName)
            .putExtra("package_label", "AW Bridge")
        try {
            startActivity(miui)
            Logx.log("Chọn \"Không hạn chế\" rồi quay lại app")
        } catch (_: Throwable) {
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
                Logx.log("Trong màn hình này: Tiết kiệm pin > Không hạn chế, và bật Tự khởi chạy")
            } catch (_: Throwable) {
                Logx.log("Vào Cài đặt > Ứng dụng > AW Bridge > Tiết kiệm pin > Không hạn chế")
            }
        }
    }

    private fun refresh() {
        val service = BridgeService.instance
        val bridge = when {
            service == null -> "TẮT"
            service.watchConnected -> "ĐÃ KẾT NỐI WATCH"
            else -> "đang chờ watch"
        }
        val access = when {
            !NotifListener.granted(this) -> "CHƯA (bấm nút 2)"
            NotifListener.instance != null -> "có, đang chạy"
            else -> "có nhưng CHƯA CHẠY (nếu không tự hết: nút 2, tắt rồi bật lại AW Bridge)"
        }
        val maps = if (service?.mapsGuiding == true) "đang dẫn đường" else "chưa thấy dẫn đường"
        val located = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val place = if (located) "có" else "CHƯA"
        val route = service?.routeSummary ?: "chưa đặt"
        val background = if (Net.unrestricted(this)) "không giới hạn" else "BỊ GIỚI HẠN (bấm nút 4)"
        val network = Net.problem(this)?.let { "LỖI: $it" } ?: "có"
        status.text = "Cầu nối: $bridge\nQuyền đọc thông báo: $access\nQuyền vị trí: $place\n" +
            "Chạy nền: $background\nMạng: $network\nGoogle Maps: $maps\nĐiểm đến trong AW Bridge: $route"
        val text = Logx.text()
        if (text != lastLog) {
            lastLog = text
            logView.text = text
        }
    }

    /** Cycles through three fake maneuvers so the watch can be tested without driving. */
    private fun sendTest() {
        val service = BridgeService.instance
        if (service == null) {
            Logx.log("Chưa bật cầu nối")
            return
        }
        if (!service.watchConnected) {
            Logx.log("Watch chưa kết nối, chưa gửi thử được")
            return
        }
        val steps = listOf(
            Triple("200 m", "Rẽ phải vào Lê Lợi", 90f),
            Triple("1,2 km", "Đi thẳng trên Nguyễn Huệ", 0f),
            Triple("50 m", "Rẽ trái vào Pasteur", -90f)
        )
        val (distance, instruction, angle) = steps[testStep % steps.size]
        testStep++
        val json = JSONObject()
            .put("a", true)
            .put("d", distance)
            .put("i", instruction)
            .put("e", "12 phút · 3,4 km")
            .toString()
            .toByteArray(Charsets.UTF_8)
        service.updateNav(json, NavParser.iconBits(arrowBitmap(angle)))
        Logx.log("Gửi thử: $distance, $instruction")
    }

    private fun arrowBitmap(angle: Float): Bitmap {
        val s = NavParser.ICON_SIZE
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        canvas.rotate(angle, s / 2f, s / 2f)
        val path = Path().apply {
            moveTo(32f, 6f)
            lineTo(54f, 30f)
            lineTo(40f, 30f)
            lineTo(40f, 58f)
            lineTo(24f, 58f)
            lineTo(24f, 30f)
            lineTo(10f, 30f)
            close()
        }
        canvas.drawPath(path, paint)
        return bmp
    }
}
