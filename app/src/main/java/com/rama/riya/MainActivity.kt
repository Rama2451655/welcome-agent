package com.rama.riya

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    // ---- palette: night indigo, peacock teal, marigold ----
    private val bg1 = 0xFF120E2B.toInt()
    private val bg2 = 0xFF2A1650.toInt()
    private val surface = 0xFF1D1745.toInt()
    private val field = 0xFF140F32.toInt()
    private val hairline = 0x1FFFFFFF
    private val teal = 0xFF22D3C5.toInt()
    private val marigold = 0xFFFFB02E.toInt()
    private val coral = 0xFFFF8A3D.toInt()
    private val rose = 0xFFFF5C7A.toInt()
    private val ink = 0xFF1B1340.toInt()
    private val textMain = 0xFFF4F0FF.toInt()
    private val muted = 0xFFA59CC9.toInt()
    private val good = 0xFF5EE6A8.toInt()

    private lateinit var prefs: SharedPreferences
    private lateinit var nameBox: EditText
    private lateinit var nameTitle: TextView
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var statusPill: LinearLayout
    private lateinit var toggleBtn: LinearLayout
    private lateinit var toggleIcon: ImageView
    private lateinit var toggleLabel: TextView
    private lateinit var orb: OrbView
    private lateinit var heardText: TextView
    private lateinit var setupSub: TextView
    private lateinit var cmdBox: LinearLayout
    private lateinit var langEn: TextView
    private lateinit var langTe: TextView

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "heard" || key == "running") runOnUiThread { refresh() }
    }
    private val checks = mutableListOf<Pair<ImageView, () -> Boolean>>()
    private var cmdBuiltFor = ""

    // ---------- small helpers ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun lp(top: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(top) }

    private fun ripple(radius: Int) = RippleDrawable(
        ColorStateList.valueOf(0x33FFFFFF), null, rounded(0xFFFFFFFF.toInt(), radius)
    )

    private fun tv(t: String, size: Float, color: Int, family: String = "sans-serif") = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        typeface = Typeface.create(family, Typeface.NORMAL)
    }

    private fun icon(res: Int, tint: Int, sizeDp: Int) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(tint)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun group(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(surface)
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1), hairline)
        }
        setPadding(dp(6), dp(6), dp(6), dp(6))
    }

    private fun divider(parent: LinearLayout, startDp: Int = 62) {
        val d = View(this).apply { setBackgroundColor(hairline) }
        parent.addView(d, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            leftMargin = dp(startDp)
            rightMargin = dp(10)
        })
    }

    private fun sectionHeader(title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(tv(title, 19f, textMain, "sans-serif-medium"))
    }

    // ---------- screen ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("riya", Context.MODE_PRIVATE)
        window.statusBarColor = bg1
        window.navigationBarColor = bg2

        val root = ScrollView(this).apply {
            isFillViewport = true
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(bg1, bg2))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(40))
        }
        root.addView(content)

        // ---------- hero ----------
        orb = OrbView(this).apply { setOnClickListener { pressFeedback(it); talkNow() } }
        content.addView(orb, LinearLayout.LayoutParams(dp(230), dp(230)).apply { gravity = Gravity.CENTER_HORIZONTAL })

        // the one orchestrated moment: the orb settles in
        orb.alpha = 0f
        orb.scaleX = 0.85f
        orb.scaleY = 0.85f
        orb.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(520).start()

        nameTitle = tv("Riya", 46f, textMain, "sans-serif-light").apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.02f
        }
        content.addView(nameTitle, lp(2))
        content.addView(tv("Say my name, then ask. English and తెలుగు.", 14f, muted).apply { gravity = Gravity.CENTER }, lp(2))

        statusDot = View(this)
        statusText = tv("", 14f, muted, "sans-serif-medium")
        statusPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(7), dp(16), dp(7))
            addView(statusDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(8) })
            addView(statusText)
        }
        content.addView(statusPill, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(14) })

        toggleIcon = icon(R.drawable.ic_play, ink, 22)
        toggleLabel = tv("Start listening", 17f, ink, "sans-serif-medium")
        toggleBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isClickable = true
            foreground = ripple(30)
            setOnClickListener { toggle() }
            addView(toggleIcon)
            addView(toggleLabel, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(10) })
        }
        content.addView(toggleBtn, lp(18))

        // ---------- last heard ----------
        val heard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0x14FFFFFF, 20)
            setPadding(dp(18), dp(14), dp(18), dp(16))
        }
        heard.addView(tv("Last heard", 13f, muted, "sans-serif-medium"))
        heardText = tv("\u2014", 18f, textMain).apply { setTypeface(typeface, Typeface.ITALIC) }
        heard.addView(heardText, lp(4))
        content.addView(heard, lp(20))

        // ---------- setup ----------
        content.addView(sectionHeader("Set up"), lp(30))
        setupSub = tv("Tap a row until it shows a green tick", 13f, muted)
        content.addView(setupSub, lp(2))
        val setup = group()
        setupRow(setup, R.drawable.ic_mic, "Microphone, contacts and calls", "So I can listen, find people and call them",
            { requestAllPermissions() },
            {
                listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
                    .all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
            })
        divider(setup)
        setupRow(setup, R.drawable.ic_lock, "Lock screen", "Device admin, so I can lock your phone",
            {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this@MainActivity, AdminReceiver::class.java))
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Needed so the assistant can lock your screen.")
                }
                startActivity(intent)
            },
            {
                (getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager)
                    .isAdminActive(ComponentName(this, AdminReceiver::class.java))
            })
        divider(setup)
        setupRow(setup, R.drawable.ic_layers, "Display over other apps", "Lets me open camera, YouTube and other apps",
            { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) },
            { Settings.canDrawOverlays(this) })
        divider(setup)
        setupRow(setup, R.drawable.ic_bolt, "Background running", "Choose Allow, so I keep listening",
            { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) },
            { (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName) })
        divider(setup)
        setupRow(setup, R.drawable.ic_access, "WhatsApp auto-send", "Accessibility \u2192 turn on Riya",
            { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            {
                Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                    ?.contains(packageName) == true
            })
        divider(setup)
        setupRow(setup, R.drawable.ic_open, "Pop-ups in background", "Xiaomi, Oppo, Vivo: allow pop-ups and autostart",
            { openPermEditor() }, null)
        content.addView(setup, lp(12))

        // ---------- make it yours ----------
        content.addView(sectionHeader("Make it yours"), lp(30))
        val mine = group().apply { setPadding(dp(16), dp(16), dp(16), dp(18)) }

        mine.addView(tv("Wake name", 13f, muted, "sans-serif-medium"))
        nameBox = EditText(this).apply {
            hint = "Riya, Jarvis, రియా\u2026"
            setText(prefs.getString("name", "Riya"))
            setTextColor(textMain)
            setHintTextColor(muted)
            textSize = 17f
            setSingleLine()
            background = null
            setPadding(dp(12), dp(14), 0, dp(14))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { updateTitle() }
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            })
        }
        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(field, 14)
            setPadding(dp(14), 0, dp(14), 0)
            addView(icon(R.drawable.ic_person, muted, 20))
            addView(nameBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        mine.addView(nameRow, lp(8))
        mine.addView(tv("For Telugu, you can type the name in Telugu letters.", 12f, muted), lp(6))

        mine.addView(tv("Language", 13f, muted, "sans-serif-medium"), lp(20))
        langEn = tv("English", 15f, muted, "sans-serif-medium").apply { gravity = Gravity.CENTER; setPadding(0, dp(11), 0, dp(11)) }
        langTe = tv("తెలుగు", 15f, muted, "sans-serif-medium").apply { gravity = Gravity.CENTER; setPadding(0, dp(11), 0, dp(11)) }
        langEn.setOnClickListener { prefs.edit().putString("lang", "en").apply(); styleLang() }
        langTe.setOnClickListener { prefs.edit().putString("lang", "te").apply(); styleLang() }
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(field, 16)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(langEn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(langTe, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        mine.addView(seg, lp(8))
        styleLang()

        mine.addView(tv("AI key", 13f, muted, "sans-serif-medium"), lp(20))
        val keyBox = EditText(this).apply {
            hint = "gsk_\u2026"
            setText(prefs.getString("apikey", ""))
            setTextColor(textMain)
            setHintTextColor(muted)
            textSize = 16f
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = null
            setPadding(dp(12), dp(14), 0, dp(14))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    prefs.edit().putString("apikey", s.toString().trim()).apply()
                }
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            })
        }
        val keyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(field, 14)
            setPadding(dp(14), 0, dp(14), 0)
            addView(icon(R.drawable.ic_key, muted, 20))
            addView(keyBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        mine.addView(keyRow, lp(8))
        mine.addView(tv("Free key from console.groq.com/keys. Leave empty to use basic commands only.", 12f, muted), lp(6))
        content.addView(mine, lp(12))

        // ---------- try saying ----------
        content.addView(sectionHeader("Try saying"), lp(30))
        cmdBox = group().apply { setPadding(dp(8), dp(8), dp(8), dp(8)) }
        content.addView(cmdBox, lp(12))

        setContentView(root)
    }

    private fun pressFeedback(v: View) {
        v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
        }.start()
    }

    private fun styleLang() {
        val te = prefs.getString("lang", "en") == "te"
        for ((view, selected) in listOf(langEn to !te, langTe to te)) {
            if (selected) {
                view.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFFFFC857.toInt(), marigold))
                    .apply { cornerRadius = dp(12).toFloat() }
                view.setTextColor(ink)
            } else {
                view.background = null
                view.setTextColor(muted)
            }
        }
    }

    private fun setupRow(parent: LinearLayout, iconRes: Int, title: String, desc: String, onClick: () -> Unit, check: (() -> Boolean)?) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(12), dp(10), dp(12))
            isClickable = true
            foreground = ripple(18)
            setOnClickListener { onClick() }
        }
        val tile = FrameLayout(this).apply { background = rounded(0x2622D3C5, 14) }
        tile.addView(icon(iconRes, teal, 20), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        row.addView(tile, LinearLayout.LayoutParams(dp(42), dp(42)))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(title, 15f, textMain, "sans-serif-medium"))
        col.addView(tv(desc, 12.5f, muted), lp(1))
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(14)
            rightMargin = dp(8)
        })

        val status = icon(R.drawable.ic_chevron, muted, 24)
        row.addView(status)
        parent.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (check != null) checks.add(status to check)
        else { status.setImageResource(R.drawable.ic_open); status.setColorFilter(marigold) }
    }

    private fun commandRow(iconRes: Int, wake: String, rest: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(11), dp(10), dp(11))
        }
        row.addView(icon(iconRes, teal, 22))
        val sb = SpannableStringBuilder()
        if (wake.isNotEmpty()) {
            sb.append(wake)
            sb.setSpan(ForegroundColorSpan(marigold), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("  ")
        }
        val start = sb.length
        sb.append(rest)
        sb.setSpan(ForegroundColorSpan(textMain), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        row.addView(TextView(this).apply {
            text = sb
            textSize = 14.5f
            setLineSpacing(0f, 1.15f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(14) })
        return row
    }

    private fun buildCommands(name: String) {
        if (cmdBuiltFor == name) return
        cmdBuiltFor = name
        cmdBox.removeAllViews()
        val items = listOf(
            Triple(R.drawable.ic_apps, name, "open any app, like Instagram"),
            Triple(R.drawable.ic_call, name, "call Vinod"),
            Triple(R.drawable.ic_chat, name, "send WhatsApp to Vinod saying hello"),
            Triple(R.drawable.ic_music, name, "play a song"),
            Triple(R.drawable.ic_camera, name, "open camera"),
            Triple(R.drawable.ic_lock, name, "lock screen"),
            Triple(R.drawable.ic_flash, name, "flashlight on"),
            Triple(R.drawable.ic_volume, name, "volume up, down or 50"),
            Triple(R.drawable.ic_clock, name, "what's the time"),
            Triple(R.drawable.ic_spark, name, "ask anything in your own words"),
            Triple(R.drawable.ic_translate, "", "Telugu works too: యూట్యూబ్ ఓపెన్ చెయ్యి")
        )
        items.forEachIndexed { i, (res, wake, rest) ->
            cmdBox.addView(commandRow(res, wake, rest))
            if (i < items.size - 1) divider(cmdBox, 56)
        }
    }

    private fun openPermEditor() {
        try {
            startActivity(Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                putExtra("extra_pkgname", packageName)
            })
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun requestAllPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(perms.toTypedArray(), 1)
    }

    private fun isRunning() = prefs.getBoolean("running", false)

    private fun currentName() = nameBox.text.toString().trim().ifEmpty { "Riya" }

    private fun toggle() {
        val svc = Intent(this, RiyaService::class.java)
        if (isRunning()) {
            stopService(svc)
            toast("Stopped")
        } else {
            val name = currentName()
            prefs.edit().putString("name", name).apply()
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                toast("Allow microphone first (first row)")
                requestAllPermissions()
                return
            }
            stopService(svc)
            startForegroundService(svc)
            toast("Started. Say: $name what's the time")
        }
        toggleBtn.postDelayed({ refresh() }, 700)
    }

    private fun talkNow() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Allow microphone first (first row)")
            requestAllPermissions()
            return
        }
        prefs.edit().putString("name", currentName()).apply()
        startForegroundService(Intent(this, RiyaService::class.java).setAction("WAKE"))
        toggleBtn.postDelayed({ refresh() }, 700)
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        refresh()
    }

    override fun onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun updateTitle() {
        nameTitle.text = currentName()
    }

    private fun refresh() {
        // setup ticks + progress
        var done = 0
        for ((mark, check) in checks) {
            val ok = check()
            if (ok) done++
            mark.setImageResource(if (ok) R.drawable.ic_check else R.drawable.ic_chevron)
            mark.setColorFilter(if (ok) good else muted)
        }
        setupSub.text = if (done == checks.size) "All set. You're ready to go."
            else "$done of ${checks.size} done. Tap a row to finish it."

        heardText.text = "\u201C" + prefs.getString("heard", "nothing yet") + "\u201D"
        updateTitle()
        buildCommands(currentName())

        val name = currentName()
        val running = isRunning()
        orb.setActive(running)
        if (running) {
            val ai = if (prefs.getString("apikey", "").isNullOrBlank()) "" else "  \u2022  AI on"
            statusText.text = "Listening for \u201C$name\u201D$ai"
            statusText.setTextColor(teal)
            statusDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(teal) }
            statusPill.background = rounded(0x2622D3C5, 20)
            toggleLabel.text = "Stop"
            toggleLabel.setTextColor(0xFFFFFFFF.toInt())
            toggleIcon.setImageResource(R.drawable.ic_stop)
            toggleIcon.setColorFilter(0xFFFFFFFF.toInt())
            toggleBtn.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFFFF7A93.toInt(), rose)).apply { cornerRadius = dp(30).toFloat() }
        } else {
            statusText.text = "Not listening"
            statusText.setTextColor(muted)
            statusDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(muted) }
            statusPill.background = rounded(0x14FFFFFF, 20)
            toggleLabel.text = "Start listening"
            toggleLabel.setTextColor(ink)
            toggleIcon.setImageResource(R.drawable.ic_play)
            toggleIcon.setColorFilter(ink)
            toggleBtn.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFFFFC857.toInt(), marigold)).apply { cornerRadius = dp(30).toFloat() }
        }
    }
}
