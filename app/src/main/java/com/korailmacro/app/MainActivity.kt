package com.korailmacro.app

import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.korailmacro.app.databinding.ActivityMainBinding
import com.korailmacro.app.korail.KorailApi
import kotlinx.coroutines.launch
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var selectedDate: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        restoreFromPrefs()
        requestNotificationPermissionIfNeeded()

        binding.buttonPickDate.setOnClickListener { showDatePicker() }

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
    }

    private fun showDatePicker() {
        val cal = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                selectedDate = "%04d%02d%02d".format(year, month + 1, dayOfMonth)
                binding.textSelectedDate.text = "선택된 날짜: $selectedDate"
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
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
        prefs.telegramToken = binding.editTelegramToken.text.toString().trim()
        prefs.telegramChatId = binding.editTelegramChatId.text.toString().trim()

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
