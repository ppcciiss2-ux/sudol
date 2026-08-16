package com.korailmacro.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class KorailMacroApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(
            if (ThemePrefs.isDarkMode(this)) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }
}
