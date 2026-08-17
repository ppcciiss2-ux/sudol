package com.korailmacro.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CalendarView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.korailmacro.app.databinding.ActivityMainBinding
import com.korailmacro.app.databinding.DialogSettingsBinding
import com.korailmacro.app.korail.KorailApi
import com.korailmacro.app.korail.Stations
import com.korailmacro.app.korail.TrainType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var selectedDate: String = ""

    // KORAIL-app-style picker palette — resolved lazily (not at construction time, before
    // the Activity is attached to a Context) and re-resolved on every access so dialogs
    // always match the currently active light/dark mode.
    private val colorBg get() = ContextCompat.getColor(this, R.color.bgColor)
    private val colorCard get() = ContextCompat.getColor(this, R.color.cardColor)
    private val colorBorder get() = ContextCompat.getColor(this, R.color.borderColor)
    private val colorAccent get() = ContextCompat.getColor(this, R.color.colorAccent)
    private val colorTextPrimary get() = ContextCompat.getColor(this, R.color.textPrimary)
    private val colorTextSecondary get() = ContextCompat.getColor(this, R.color.textSecondary)

    /** 오른쪽 스와이프 -> 좌석 현황 화면. 관찰만 하고 소비는 하지 않으므로 기존 스크롤/탭 동작에 영향 없음. */
    private val gestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (dx > 150 && abs(dx) > abs(dy) && abs(velocityX) > 300) {
                    startActivity(Intent(this@MainActivity, SeatStatusActivity::class.java))
                    overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.slide_out_right)
                    return true
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        restoreFromPrefs()
        requestNotificationPermissionIfNeeded()

        binding.buttonSettings.setOnClickListener { showSettingsDialog() }
        binding.buttonPickDate.setOnClickListener { showDateAndStartTimePicker() }
        binding.editDepStation.setOnClickListener { showStationPicker(binding.editDepStation, isDeparture = true) }
        binding.editArrStation.setOnClickListener { showStationPicker(binding.editArrStation, isDeparture = false) }
        binding.editStartTime.setOnClickListener { showDateAndStartTimePicker() }
        binding.editEndTime.setOnClickListener { showEndTimePicker() }
        binding.editAdultCount.setOnClickListener { showAdultCountPicker() }

        binding.buttonStart.setOnClickListener { onStartClicked() }
        binding.buttonStop.setOnClickListener {
            startService(Intent(this, ReservationService::class.java).apply {
                action = ReservationService.ACTION_STOP
            })
        }

        binding.buttonIgnoreBattery.setOnClickListener { requestIgnoreBatteryOptimizations() }

        lifecycleScope.launch {
            ServiceBus.log.collect {
                binding.textLog.text = it
                binding.scrollLog.post { binding.scrollLog.fullScroll(View.FOCUS_DOWN) }
            }
        }
        lifecycleScope.launch {
            ServiceBus.running.collect { running ->
                binding.buttonStart.isEnabled = !running
                binding.buttonStop.isEnabled = running
            }
        }
    }

    private fun restoreFromPrefs() {
        binding.editDepStation.setText(prefs.depStation)
        binding.editArrStation.setText(prefs.arrStation)
        binding.editStartTime.setText(prefs.startTime)
        binding.editEndTime.setText(prefs.endTime)
        binding.editAdultCount.setText(prefs.adultCount.toString())

        selectedDate = prefs.travelDate
        if (selectedDate.isNotBlank()) {
            binding.textSelectedDate.text = "선택된 날짜: $selectedDate"
        }

        if (prefs.seatType == KorailApi.SEAT_SPECIAL) {
            binding.rbSpecialSeat.isChecked = true
        } else {
            binding.rbGeneralSeat.isChecked = true
        }

        val types = prefs.trainTypes
        binding.cbKtx.isChecked = TrainType.KTX in types
        binding.cbItxSaemaeul.isChecked = TrainType.ITX_SAEMAEUL in types
        binding.cbMugunghwa.isChecked = TrainType.MUGUNGHWA in types
        binding.cbItxCheongchun.isChecked = TrainType.ITX_CHEONGCHUN in types
    }

    /** Logs in immediately (independent of the reservation loop) so the user gets a pass/fail answer before configuring the rest of the form. */
    private fun runLoginTest(loginType: String, loginId: String, password: String, button: Button) {
        prefs.loginType = loginType
        prefs.loginId = loginId
        prefs.password = password

        button.isEnabled = false
        ServiceBus.append("로그인 확인 중...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                KorailApi().login(loginType, loginId, password)
                ServiceBus.append("✅ 로그인 성공")
            } catch (e: Exception) {
                ServiceBus.append("❌ 로그인 실패: ${e.message}")
            } finally {
                runOnUiThread { button.isEnabled = true }
            }
        }
    }

    /** 환경설정: 로그인, 새로고침 간격, 다크모드, 텔레그램 알림 — 예매 조건과 분리된 설정들을 한 곳에 모은 다이얼로그. */
    private fun showSettingsDialog() {
        val db = DialogSettingsBinding.inflate(layoutInflater)

        db.editLoginId.setText(prefs.loginId)
        db.editPassword.setText(prefs.password)
        when (prefs.loginType) {
            KorailApi.LOGIN_TYPE_MEMBERSHIP -> db.rbMembership.isChecked = true
            KorailApi.LOGIN_TYPE_PHONE -> db.rbPhone.isChecked = true
            else -> db.rbEmail.isChecked = true
        }
        db.editTelegramToken.setText(prefs.telegramToken)
        db.editTelegramChatId.setText(prefs.telegramChatId)
        db.switchDarkMode.isChecked = ThemePrefs.isDarkMode(this)

        val intervalOptions = intArrayOf(5, 10, 15, 30, 60)
        var pickedInterval = intervalOptions.minByOrNull { kotlin.math.abs(it - prefs.pollIntervalSec) } ?: 5

        lateinit var refreshIntervalChips: () -> Unit
        refreshIntervalChips = {
            db.pollIntervalChipRow.removeAllViews()
            for (sec in intervalOptions) {
                val c = chip("${sec}초", selected = sec == pickedInterval) {
                    pickedInterval = sec
                    refreshIntervalChips()
                }
                db.pollIntervalChipRow.addView(c, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(6)
                })
            }
        }
        refreshIntervalChips()

        db.switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            ThemePrefs.setDarkMode(this, isChecked)
            // Triggers an Activity recreate, which dismisses this dialog as a side effect — that's fine.
            AppCompatDelegate.setDefaultNightMode(
                if (isChecked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
        }

        db.buttonTestLogin.setOnClickListener {
            val loginId = db.editLoginId.text.toString().trim()
            val password = db.editPassword.text.toString()
            if (loginId.isBlank() || password.isBlank()) {
                Toast.makeText(this, "아이디/비밀번호를 입력하세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val loginType = when (db.radioGroupLoginType.checkedRadioButtonId) {
                db.rbMembership.id -> KorailApi.LOGIN_TYPE_MEMBERSHIP
                db.rbPhone.id -> KorailApi.LOGIN_TYPE_PHONE
                else -> KorailApi.LOGIN_TYPE_EMAIL
            }
            runLoginTest(loginType, loginId, password, db.buttonTestLogin)
        }

        val dialog = AlertDialog.Builder(this).setView(db.root).create()

        db.buttonSaveSettings.setOnClickListener {
            val loginId = db.editLoginId.text.toString().trim()
            val password = db.editPassword.text.toString()
            prefs.loginType = when (db.radioGroupLoginType.checkedRadioButtonId) {
                db.rbMembership.id -> KorailApi.LOGIN_TYPE_MEMBERSHIP
                db.rbPhone.id -> KorailApi.LOGIN_TYPE_PHONE
                else -> KorailApi.LOGIN_TYPE_EMAIL
            }
            prefs.loginId = loginId
            prefs.password = password
            prefs.pollIntervalSec = pickedInterval
            prefs.telegramToken = db.editTelegramToken.text.toString().trim()
            prefs.telegramChatId = db.editTelegramChatId.text.toString().trim()
            Toast.makeText(this, "환경설정 저장됨", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    /** KORAIL-style chip picker for 성인 인원수 (1~9명), tap-to-select-and-dismiss like the end-time picker. */
    private fun showAdultCountPicker() {
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorBg)
            setPadding(pad, pad, pad, pad)
        }
        root.addView(dialogTitle("인원수 선택"))

        val current = binding.editAdultCount.text.toString().trim().toIntOrNull() ?: 1

        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scroll.addView(row)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })

        lateinit var dialog: AlertDialog
        for (n in 1..9) {
            val c = chip("${n}명", selected = n == current) {
                binding.editAdultCount.setText(n.toString())
                dialog.dismiss()
            }
            row.addView(c, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6)
            })
        }

        dialog = AlertDialog.Builder(this).setView(root).create()
        dialog.show()
        dialog.window?.setBackgroundDrawable(GradientDrawable().apply { setColor(colorBg) })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun roundedDrawable(fillColor: Int, strokeColor: Int? = null, radiusDp: Int = 10): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fillColor)
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    /** A KORAIL-style pill chip (역/시간대/자음 tabs all reuse this). */
    private fun chip(text: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(if (selected) Color.WHITE else colorTextSecondary)
            textSize = 14f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = roundedDrawable(
                if (selected) colorAccent else colorCard,
                if (selected) colorAccent else colorBorder,
                18
            )
            setOnClickListener { onClick() }
        }

    private fun dialogTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(colorTextPrimary)
        textSize = 18f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    /** Real KORAIL station names only, chosen from [Stations.NAMES] — no free-text entry. Styled after the KORAIL app's own 역 선택 screen: search box (with 초성 support), recent-route chips, and 자음 jump tabs. */
    private fun showStationPicker(target: EditText, isDeparture: Boolean) {
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorBg)
            setPadding(pad, pad, pad, pad)
        }
        root.addView(dialogTitle(if (isDeparture) "출발역 선택" else "도착역 선택"))

        val search = EditText(this).apply {
            hint = "역 이름 또는 초성 입력"
            setHintTextColor(colorTextSecondary)
            setTextColor(colorTextPrimary)
            background = roundedDrawable(colorCard, colorBorder)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setSingleLine()
        }
        root.addView(search, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        val recentRoutes = prefs.recentRoutes()
        lateinit var dialog: AlertDialog
        if (recentRoutes.isNotEmpty()) {
            val recentScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            val recentRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            recentRoutes.forEach { (dep, arr) ->
                val c = chip("$dep → $arr", selected = false) {
                    binding.editDepStation.setText(dep)
                    binding.editArrStation.setText(arr)
                    dialog.dismiss()
                }
                recentRow.addView(c, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(8)
                })
            }
            recentScroll.addView(recentRow)
            root.addView(recentScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            })
        }

        val tabScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabScroll.addView(tabRow)
        root.addView(tabScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })

        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, Stations.NAMES.toMutableList()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                view.setTextColor(colorTextPrimary)
                view.setBackgroundColor(colorBg)
                view.setPadding(dp(4), dp(14), dp(4), dp(14))
                return view
            }
        }
        val listView = ListView(this).apply {
            this.adapter = adapter
            divider = GradientDrawable().apply { setColor(colorBorder) }
            dividerHeight = 1
        }
        root.addView(listView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(340)).apply {
            topMargin = dp(8)
        })

        Stations.AVAILABLE_INITIALS.forEach { initial ->
            val t = chip(initial.toString(), selected = false) {
                val idx = Stations.firstIndexOf(initial)
                if (idx >= 0) listView.setSelection(idx)
            }
            tabRow.addView(t, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6)
            })
        }

        dialog = AlertDialog.Builder(this).setView(root).create()

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString().orEmpty()
                val filtered = Stations.NAMES.filter { Stations.matches(it, query) }
                adapter.clear()
                adapter.addAll(filtered)
            }
        })
        listView.setOnItemClickListener { _, _, position, _ ->
            target.setText(adapter.getItem(position))
            dialog.dismiss()
        }
        dialog.show()
        dialog.window?.apply {
            setGravity(Gravity.TOP)
            setBackgroundDrawable(GradientDrawable().apply { setColor(colorBg) })
        }
    }

    /** KORAIL-style "가는날 선택": a calendar plus hour-band chips (0~23시) merged into one confirm step. */
    private fun showDateAndStartTimePicker() {
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorBg)
            setPadding(pad, pad, pad, pad)
        }
        root.addView(dialogTitle("가는날 선택"))

        val cal = Calendar.getInstance()
        if (selectedDate.length == 8) {
            runCatching {
                cal.set(
                    selectedDate.substring(0, 4).toInt(),
                    selectedDate.substring(4, 6).toInt() - 1,
                    selectedDate.substring(6, 8).toInt()
                )
            }
        }
        var pickedDate = selectedDate.ifBlank {
            "%04d%02d%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        }

        val calendarCard = FrameLayout(this).apply {
            // Was hardcoded white — but the theme also makes CalendarView's day-number text
            // white in dark mode, so white-on-white made the whole calendar invisible.
            // colorCard/colorBorder track the same light/dark mode as the text does.
            background = roundedDrawable(colorCard, colorBorder, 10)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val calendarView = CalendarView(this).apply { date = cal.timeInMillis }
        calendarCard.addView(calendarView)
        root.addView(calendarCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        val hourLabel = TextView(this).apply {
            text = "시간대 선택"
            setTextColor(colorTextPrimary)
            textSize = 15f
        }
        root.addView(hourLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })

        var pickedHour = binding.editStartTime.text.toString().take(2).toIntOrNull() ?: 0

        val hourScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val hourRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hourScroll.addView(hourRow)
        root.addView(hourScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        val summary = TextView(this).apply {
            setTextColor(colorTextSecondary)
            textSize = 14f
        }
        root.addView(summary, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })

        fun updateSummary() {
            val y = pickedDate.substring(0, 4)
            val m = pickedDate.substring(4, 6).toInt()
            val d = pickedDate.substring(6, 8).toInt()
            summary.text = "${y}년 ${m}월 ${d}일 · ${pickedHour}시 이후"
        }

        lateinit var refreshHourChips: () -> Unit
        refreshHourChips = {
            hourRow.removeAllViews()
            for (h in 0..23) {
                val c = chip("${h}시", selected = h == pickedHour) {
                    pickedHour = h
                    refreshHourChips()
                    updateSummary()
                }
                hourRow.addView(c, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(6)
                })
            }
        }
        refreshHourChips()
        updateSummary()

        calendarView.setOnDateChangeListener { _, year, month, dayOfMonth ->
            pickedDate = "%04d%02d%02d".format(year, month + 1, dayOfMonth)
            updateSummary()
        }

        val confirmBtn = Button(this).apply {
            text = "선택 완료"
            setBackgroundColor(colorAccent)
            setTextColor(Color.WHITE)
        }
        root.addView(confirmBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })

        val dialog = AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(root) }).create()
        confirmBtn.setOnClickListener {
            selectedDate = pickedDate
            binding.textSelectedDate.text = "선택된 날짜: $selectedDate"
            binding.editStartTime.setText("%02d00".format(pickedHour))
            dialog.dismiss()
        }
        dialog.show()
        dialog.window?.setBackgroundDrawable(GradientDrawable().apply { setColor(colorBg) })
    }

    /** KORAIL-style hour-band chips for the (optional) end-time filter, with a "선택 안함" clear option. */
    private fun showEndTimePicker() {
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorBg)
            setPadding(pad, pad, pad, pad)
        }
        root.addView(dialogTitle("종료시각 선택"))

        val currentHour = binding.editEndTime.text.toString().take(2).toIntOrNull()

        val hourScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val hourRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hourScroll.addView(hourRow)
        root.addView(hourScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })

        lateinit var dialog: AlertDialog
        for (h in 0..23) {
            val c = chip("${h}시", selected = currentHour == h) {
                binding.editEndTime.setText("%02d00".format(h))
                dialog.dismiss()
            }
            hourRow.addView(c, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6)
            })
        }

        val clearChip = chip("선택 안함", selected = currentHour == null) {
            binding.editEndTime.setText("")
            dialog.dismiss()
        }
        root.addView(clearChip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })

        dialog = AlertDialog.Builder(this).setView(root).create()
        dialog.show()
        dialog.window?.setBackgroundDrawable(GradientDrawable().apply { setColor(colorBg) })
    }

    private fun onStartClicked() {
        val dep = binding.editDepStation.text.toString().trim()
        val arr = binding.editArrStation.text.toString().trim()
        val startTime = binding.editStartTime.text.toString().trim().ifBlank { "000000" }.padEnd(6, '0')
        val endTimeRaw = binding.editEndTime.text.toString().trim()
        val endTime = if (endTimeRaw.isBlank()) "" else endTimeRaw.padEnd(6, '0')
        val adultCount = binding.editAdultCount.text.toString().trim().toIntOrNull() ?: 1

        if (prefs.loginId.isBlank() || prefs.password.isBlank()) {
            Toast.makeText(this, "환경설정(⚙)에서 코레일 로그인 정보를 입력하세요", Toast.LENGTH_SHORT).show()
            return
        }
        if (dep.isBlank() || arr.isBlank()) {
            Toast.makeText(this, "출발역/도착역을 입력하세요", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedDate.isBlank()) {
            Toast.makeText(this, "날짜를 선택하세요", Toast.LENGTH_SHORT).show()
            return
        }
        val selectedTypes = buildSet {
            if (binding.cbKtx.isChecked) add(TrainType.KTX)
            if (binding.cbItxSaemaeul.isChecked) add(TrainType.ITX_SAEMAEUL)
            if (binding.cbMugunghwa.isChecked) add(TrainType.MUGUNGHWA)
            if (binding.cbItxCheongchun.isChecked) add(TrainType.ITX_CHEONGCHUN)
        }
        if (selectedTypes.isEmpty()) {
            Toast.makeText(this, "열차 종류를 하나 이상 선택하세요", Toast.LENGTH_SHORT).show()
            return
        }

        prefs.depStation = dep
        prefs.arrStation = arr
        prefs.travelDate = selectedDate
        prefs.startTime = startTime
        prefs.endTime = endTime
        prefs.adultCount = adultCount
        prefs.seatType = if (binding.radioGroupSeatType.checkedRadioButtonId == binding.rbSpecialSeat.id)
            KorailApi.SEAT_SPECIAL else KorailApi.SEAT_GENERAL
        prefs.trainTypes = selectedTypes
        prefs.pushRecentRoute(dep, arr)

        ServiceBus.clear()
        ContextCompat.startForegroundService(this, Intent(this, ReservationService::class.java))
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } else {
            Toast.makeText(this, "이미 배터리 최적화에서 제외되어 있습니다", Toast.LENGTH_SHORT).show()
        }
    }
}
