package com.korailmacro.app.notify

import okhttp3.CertificatePinner
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class TelegramNotifier(private val botToken: String, private val chatId: String) {

    companion object {
        // Pinned leaf + issuing CA + root for api.telegram.org (fetched 2026-08-17),
        // same rationale as KorailApi's pinner — CA/root pins survive routine leaf renewal.
        private val TELEGRAM_CERT_PINNER = CertificatePinner.Builder()
            .add(
                "api.telegram.org",
                "sha256/AgyCmTysFOI6aQCSyQJ+QIXpnGn0v7n+D+mv6jWAtQc=", // leaf, expires 2026-12-13
                "sha256/8Rw90Ej3Ttt8RRkrg+WYDS9n7IS03bk5bjP/UXPtaY8=", // Go Daddy Secure Certificate Authority - G2
                "sha256/Ko8tivDrEjiY90yGasP6ZpBU4jwXvHqVvQI0GS3GNdA=" // Go Daddy Root Certificate Authority - G2
            )
            .build()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .certificatePinner(TELEGRAM_CERT_PINNER)
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
