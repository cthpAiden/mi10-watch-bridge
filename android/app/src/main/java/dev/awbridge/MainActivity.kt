package dev.awbridge

import android.Manifest
import android.app.Activity
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
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private val ui = Handler(Looper.getMainLooper())
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

        column.addView(button("1. Cấp quyền Bluetooth") { askPermissions() })
        column.addView(button("2. Cho phép đọc thông báo") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        column.addView(button("3. Bật cầu nối") { BridgeService.start(this) })
        column.addView(button("Tắt cầu nối") { BridgeService.stop(this) })
        column.addView(button("Gửi thử chỉ đường lên watch") { sendTest() })

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
        if (Build.VERSION.SDK_INT >= 31) {
            wanted.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            wanted.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (wanted.isEmpty()) {
            Logx.log("Bản Android này không cần cấp quyền Bluetooth riêng")
        } else {
            requestPermissions(wanted.toTypedArray(), 1)
        }
    }

    private fun notificationAccessGranted(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return enabled.contains(packageName)
    }

    private fun refresh() {
        val service = BridgeService.instance
        val bridge = when {
            service == null -> "TẮT"
            service.watchConnected -> "ĐÃ KẾT NỐI WATCH"
            else -> "đang chờ watch"
        }
        val access = if (notificationAccessGranted()) "có" else "CHƯA"
        status.text = "Cầu nối: $bridge\nQuyền đọc thông báo: $access"
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
