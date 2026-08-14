package com.korailmacro.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class Prefs(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sp: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "korail_macro_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var loginType: String
        get() = sp.getString("loginType", "5") ?: "5"
        set(value) = sp.edit().putString("loginType", value).apply()

    var loginId: String
        get() = sp.getString("loginId", "") ?: ""
        set(value) = sp.edit().putString("loginId", value).apply()

    var password: String
        get() = sp.getString("password", "") ?: ""
        set(value) = sp.edit().putString("password", value).apply()

    var depStation: String
        get() = sp.getString("depStation", "") ?: ""
        set(value) = sp.edit().putString("depStation", value).apply()

    var arrStation: String
        get() = sp.getString("arrStation", "") ?: ""
        set(value) = sp.edit().putString("arrStation", value).apply()

    var travelDate: String
        get() = sp.getString("travelDate", "") ?: ""
        set(value) = sp.edit().putString("travelDate", value).apply()

    var startTime: String
        get() = sp.getString("startTime", "000000") ?: "000000"
        set(value) = sp.edit().putString("startTime", value).apply()

    var endTime: String
        get() = sp.getString("endTime", "") ?: ""
        set(value) = sp.edit().putString("endTime", value).apply()

    var adultCount: Int
        get() = sp.getInt("adultCount", 1)
        set(value) = sp.edit().putInt("adultCount", value).apply()

    var seatType: String
        get() = sp.getString("seatType", "1") ?: "1"
        set(value) = sp.edit().putString("seatType", value).apply()

    var pollIntervalSec: Int
        get() = sp.getInt("pollIntervalSec", 5)
        set(value) = sp.edit().putInt("pollIntervalSec", value).apply()

    var telegramToken: String
        get() = sp.getString("telegramToken", "") ?: ""
        set(value) = sp.edit().putString("telegramToken", value).apply()

    var telegramChatId: String
        get() = sp.getString("telegramChatId", "") ?: ""
        set(value) = sp.edit().putString("telegramChatId", value).apply()
}
