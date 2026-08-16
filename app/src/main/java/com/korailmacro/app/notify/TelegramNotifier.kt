package com.korailmacro.app.notify

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class TelegramNotifier(private val botToken: String, private val chatId: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun send(text: String): Boolean {
        if (botToken.isBlank() || chatId.isBlank()) return false
        val url = "https://api.telegram.org/bot$botToken/sendMessage"
        val body = FormBody.Builder()
            .add("chat_id", chatId)
            .add("text", text)
            .build()
        val req = Request.Builder().url(url).post(body).build()
        return try {
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }
}
