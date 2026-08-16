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
import android.view.Gravity
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.korailmacro.app.databinding.ActivityMainBinding
import com.korailmacro.app.korail.KorailApi
import com.korailmacro.app.korail.Stations
import com.korailmacro.app.korail.TrainType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var selectedDate: String = ""

    // KORAIL-app-style dark picker palette.
    private val colorBg = Color.parseColor("#14161C")
    private val colorCard = Color.parseColor("#1E212B")
    private val colorBorder = Color.parseColor("#33384A")
    private val colorAccent = Color.parseColor("#2F80FF")
    private val colorTextSecondary = Color.parseColor("#9AA0AC")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        restoreFromPrefs()
        requestNotificationPermissionIfNeeded()

        binding.buttonTestLogin.setOnClickListener { onTestLoginClicked() }
        binding.buttonPickDate.setOnClickListener { showDateAndStartTimePicker() }
        binding.editDepStation.setOnClickListener { showStationPicker(binding.editDepStation, isDeparture = true) }
        binding.editArrStation.setOnClickListener { showStationPicker(binding.editArrStation, isDeparture = false) }
        binding.editStartTime.setOnClickListener { showDateAndStartTimePicker() }
        binding.editEndTime.setOnClickListener { showEndTimePicker() }

        binding.buttonStart.setOnClickListener { onStartClicked() }
        binding.buttonStop.setOnClickListener {
            startService(Intent(this, ReservationService::class.java).apply {
                action = ReservationService.ACTION_STOP
            })
        }

        binding.buttonIgnoreBattery.setOnClickListener { requestIgnoreBatteryOptimizations() }

        lifecycleScope.launch {
            ServiceBus.log.collect { binding.textLog.text = it }
        }
        lifecycleScope.launch {
            ServiceBus.running.collect { running ->
                binding.buttonStart.isEnabled = !running
                binding.buttonStop.isEnabled = running
            }
        }
    }

    private fun restoreFromPrefs() {
        binding.editLoginId.setText(prefs.loginId)
        binding.editPassword.setText(prefs.password)
        binding.editDepStation.setText(prefs.depStation)
        binding.editArrStation.setText(prefs.arrStation)
        binding.editStartTime.setText(prefs.startTime)
        binding.editEndTime.setText(prefs.endTime)
        binding.editAdultCount.setText(prefs.adultCount.toString())
        binding.editPollInterval.setText(prefs.pollIntervalSec.toString())
        binding.editTelegramToken.setText(prefs.telegramToken)
        binding.editTelegramChatId.setText(prefs.telegramChatId)

        selectedDate = prefs.travelDate
        if (selectedDate.isNotBlank()) {
            binding.textSelectedDate.text = "선택된 날짜: $selectedDate"
        }

        when (prefs.loginType) {
            KorailApi.LOGIN_TYPE_MEMBERSHIP -> binding.rbMembership.isChecked = true
            KorailApi.LOGIN_TYPE_PHONE -> binding.rbPhone.isChecked = true
            else -> binding.rbEmail.isChecked = true
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
    private fun onTestLoginClicked() {
        val loginId = binding.editLoginId.text.toString().trim()
        val password = binding.editPassword.text.toString()
        if (loginId.isBlank() || password.isBlank()) {
            Toast.makeText(this, "아이디/비밀번호를 입력하세요", Toast.LENGTH_SHORT).show()
            return
        }
        val loginType = when (binding.radioGroupLoginType.checkedRadioButtonId) {
            binding.rbMembership.id -> KorailApi.LOGIN_TYPE_MEMBERSHIP
            binding.rbPhone.id -> KorailApi.LOGIN_TYPE_PHONE
            else -> KorailApi.LOGIN_TYPE_EMAIL
        }
        prefs.loginType = loginType
        prefs.loginId = loginId
        prefs.password = password

        binding.buttonTestLogin.isEnabled = false
        ServiceBus.append("로그인 확인 중...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                KorailApi().login(loginType, loginId, password)
                ServiceBus.append("✅ 로그인 성공")
            } catch (e: Exception) {
                ServiceBus.append("❌ 로그인 실패: ${e.message}")
            } finally {
                runOnUiThread { binding.buttonTestLogin.isEnabled = true }
            }
        }
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

    private fun darkTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
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
        root.addView(darkTitle(if (isDeparture) "출발역 선택" else "도착역 선택"))

        val search = EditText(this).apply {
            hint = "역 이름 또는 초성 입력"
            setHintTextColor(colorTextSecondary)
            setTextColor(Color.WHITE)
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
                view.setTextColor(Color.WHITE)
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
        root.addView(darkTitle("가는날 선택"))

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
            background = roundedDrawable(Color.WHITE, null, 10)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val calendarView = CalendarView(this).apply { date = cal.timeInMillis }
        calendarCard.addView(calendarView)
        root.addView(calendarCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        val hourLabel = TextView(this).apply {
            text = "시간대 선택"
            setTextColor(Color.WHITE)
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
        root.addView(darkTitle("종료시각 선택"))

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
        val loginId = binding.editLoginId.text.toString().trim()
        val password = binding.editPassword.text.toString()
        val dep = binding.editDepStation.text.toString().trim()
        val arr = binding.editArrStation.text.toString().trim()
        val startTime = binding.editStartTime.text.toString().trim().ifBlank { "000000" }.padEnd(6, '0')
        val endTimeRaw = binding.editEndTime.text.toString().trim()
        val endTime = if (endTimeRaw.isBlank()) "" else endTimeRaw.padEnd(6, '0')
        val adultCount = binding.editAdultCount.text.toString().trim().toIntOrNull() ?: 1
        val pollInterval = binding.editPollInterval.text.toString().trim().toIntOrNull() ?: 5

        if (loginId.isBlank() || password.isBlank()) {
            Toast.makeText(this, "코레일 로그인 정보를 입력하세요", Toast.LENGTH_SHORT).show()
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

        prefs.loginType = when (binding.radioGroupLoginType.checkedRadioButtonId) {
            binding.rbMembership.id -> KorailApi.LOGIN_TYPE_MEMBERSHIP
            binding.rbPhone.id -> KorailApi.LOGIN_TYPE_PHONE
            else -> KorailApi.LOGIN_TYPE_EMAIL
        }
        prefs.loginId = loginId
        prefs.password = password
        prefs.depStation = dep
        prefs.arrStation = arr
        prefs.travelDate = selectedDate
        prefs.startTime = startTime
        prefs.endTime = endTime
        prefs.adultCount = adultCount
        prefs.seatType = if (binding.radioGroupSeatType.checkedRadioButtonId == binding.rbSpecialSeat.id)
            KorailApi.SEAT_SPECIAL else KorailApi.SEAT_GENERAL
        prefs.pollIntervalSec = pollInterval
        prefs.trainTypes = selectedTypes
        prefs.telegramToken = binding.editTelegramToken.text.toString().trim()
        prefs.telegramChatId = binding.editTelegramChatId.text.toString().trim()
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
