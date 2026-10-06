package com.example.minilauncher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.WindowManager
import android.widget.EditText
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : Activity(), SensorEventListener {

    private lateinit var prefs: SharedPreferences
    private lateinit var sm: SensorManager
    private lateinit var detector: GestureDetector
    private lateinit var stepsView: TextView
    private lateinit var screenView: TextView
    private val slotViews = ArrayList<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("mini", MODE_PRIVATE)
        sm = getSystemService(SENSOR_SERVICE) as SensorManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(32), dp(72), dp(32), dp(72))
        }

        // Greyscale: poore launcher UI par saturation 0
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        root.setLayerType(View.LAYER_TYPE_HARDWARE, paint)

        // Top: steps + screen time
        stepsView = stat("0 steps")
        screenView = stat("screen 0m")
        root.addView(stepsView)
        root.addView(screenView)
        screenView.setOnClickListener {
            if (!hasUsageAccess()) startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        // Spacer
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        // 4 app slots (bottom)
        for (i in 0 until 4) {
            val tv = TextView(this).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
                setPadding(0, dp(14), 0, dp(14))
                setOnClickListener { launchSlot(i) }
                setOnLongClickListener { pickApp(i); true }
            }
            slotViews.add(tv)
            root.addView(tv)
        }
        setContentView(root)
        refreshSlots()

        // Gestures
        detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                // double tap: alarm/clock
                safeStart(Intent(AlarmClock.ACTION_SHOW_ALARMS)); return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) > abs(dy)) {
                    if (abs(dx) < dp(80f)) return false
                    if (dx < 0) safeStart(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) // swipe left
                    else safeStart(Intent(Intent.ACTION_DIAL))                                 // swipe right
                } else {
                    if (abs(dy) < dp(80f)) return false
                    if (dy > 0) expandNotifications()  // swipe down (swipe up par ab kuch nahi)
                    else return false
                }
                return true
            }
        })

        if (Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), 1)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        detector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        } ?: run { stepsView.text = "no step sensor" }
        updateScreenTime()
    }

    override fun onPause() {
        super.onPause()
        sm.unregisterListener(this)
    }

    @Deprecated("Launcher: back ignore")
    override fun onBackPressed() {}

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        onPause(); onResume()
    }

    // ---------- Steps ----------
    override fun onSensorChanged(e: SensorEvent) {
        val total = e.values[0].toLong()
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        var base = prefs.getLong("base", -1L)
        if (prefs.getString("day", "") != today || base < 0 || total < base) {
            base = if (prefs.getString("day", "") == today) 0L else total // reboot ho gaya to 0 se
            prefs.edit().putString("day", today).putLong("base", base).apply()
        }
        stepsView.text = "${total - base} steps"
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    // ---------- Screen time ----------
    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun updateScreenTime() {
        if (!hasUsageAccess()) { screenView.text = "screen time: tap, permission do"; return }
        val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val ms = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, System.currentTimeMillis())
            .filter { it.packageName != packageName }
            .sumOf { it.totalTimeInForeground }
        val min = ms / 60000
        screenView.text = "screen ${min / 60}h ${min % 60}m"
    }

    // ---------- Apps ----------
    private fun launchSlot(i: Int) {
        val pkg = prefs.getString("slot$i", null)
        if (pkg == null) { pickApp(i); return }
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) } ?: pickApp(i)
    }

    private fun refreshSlots() {
        for (i in 0 until 4) {
            val pkg = prefs.getString("slot$i", null)
            val label = try {
                if (pkg == null) null else packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString().lowercase()
            } catch (e: Exception) { null }
            slotViews[i].text = label ?: "+"
        }
    }

    private fun installedApps(): List<Pair<String, String>> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(i, 0)
            .map { it.loadLabel(packageManager).toString() to it.activityInfo.packageName }
            .filter { it.second != packageName }
            .sortedBy { it.first.lowercase() }
    }

    // Poori list kabhi nahi dikhti: naam type karo, sirf matching 3 apps text me aate hain
    private fun pickApp(slot: Int) {
        val apps = installedApps()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        val input = EditText(this).apply { hint = "app ka naam likho"; setSingleLine() }
        val matches = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(input); box.addView(matches)
        val dialog = AlertDialog.Builder(this).setTitle("App chuno").setView(box).create()

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                matches.removeAllViews()
                val q = s.toString().trim().lowercase()
                if (q.length < 2) return
                apps.filter { it.first.lowercase().contains(q) }.take(3).forEach { app ->
                    matches.addView(TextView(this@MainActivity).apply {
                        text = app.first.lowercase()
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                        setPadding(0, dp(12), 0, dp(12))
                        setOnClickListener {
                            prefs.edit().putString("slot$slot", app.second).apply()
                            refreshSlots()
                            dialog.dismiss()
                        }
                    })
                }
            }
        })
        dialog.show()
        input.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    // ---------- Helpers ----------
    private fun expandNotifications() {
        try {
            val s = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager")
                .getMethod("expandNotificationsPanel").invoke(s)
        } catch (e: Exception) { /* kuch phones par block hota hai */ }
    }

    private fun safeStart(i: Intent) = try { startActivity(i) } catch (e: Exception) {}

    private fun stat(t: String) = TextView(this).apply {
        text = t
        setTextColor(Color.parseColor("#BBBBBB"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setPadding(0, dp(4), 0, dp(4))
        gravity = Gravity.START
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dp(v: Float) = v * resources.displayMetrics.density
}
