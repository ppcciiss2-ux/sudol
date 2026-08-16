package com.korailmacro.app

import android.content.Context

/** Plain (unencrypted) prefs for the dark-mode toggle — read at Application startup, before Prefs' EncryptedSharedPreferences/MasterKey would be ready. */
object ThemePrefs {
    private const val PREFS_NAME = "theme_prefs"
    private const val KEY_DARK_MODE = "darkMode"

    fun isDarkMode(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_DARK_MODE, true)

    fun setDarkMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_DARK_MODE, enabled).apply()
    }
}
