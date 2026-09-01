package com.korailmacro.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.korailmacro.app.korail.KorailApi
import com.korailmacro.app.notify.TelegramNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ReservationService : Service() {

    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KorailMacro:pollLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfSafely()
            return START_NOT_STICKY
        }
        // intent is only null when the OS redelivers a start command on its own (e.g. after the
        // process was killed) rather than in direct response to the 시작 button. This service
        // makes real purchases, so it must never resume on its own — require an explicit intent
        // from MainActivity every time. START_NOT_STICKY (below) is the primary guard; this is
        // defense in depth in case a future OS restart path skips that.
        if (intent == null) return START_NOT_STICKY
        startForeground(NOTIF_ID, buildOngoingNotification("예매 조건 조회 중..."))
        wakeLock?.let { if (!it.isHeld) it.acquire(12 * 60 * 60 * 1000L) }
        startLoop()
        // Deliberately not START_STICKY: if the OS kills this process, it must stay dead until
        // the user explicitly presses 시작 again, never silently resume and re-attempt purchases.
        return START_NOT_STICKY
    }

    private fun startLoop() {
        if (job?.isActive == true) return
        val prefs = Prefs(this)

        job = CoroutineScope(Dispatchers.IO).launch {
            ServiceBus.setRunning(true)
            ServiceBus.append("서비스 시작: ${prefs.depStation} -> ${prefs.arrStation} ${prefs.travelDate} ${prefs.startTime}")

            val api = KorailApi()
            try {
                api.login(prefs.loginType, prefs.loginId, prefs.password)
                ServiceBus.append("코레일 로그인 성공")
            } catch (e: Exception) {
                ServiceBus.append("로그인 실패: ${e.message}")
                stopSelfSafely()
                return@launch
            }

            val notifier = TelegramNotifier(prefs.telegramToken, prefs.telegramChatId)
            val intervalMs = prefs.pollIntervalSec.coerceAtLeast(1) * 1000L

            while (isActive) {
                try {
                    val endTime = prefs.endTime
                    val trains = api.searchTrain(
                        prefs.depStation, prefs.arrStation,
                        prefs.travelDate, prefs.startTime, prefs.adultCount, endTime
                    )
                    val allowedTypes = prefs.trainTypes
                    // trains is the raw search result — searchTrain fetches whole 10-train pages
                    // and only stops once a page's LAST train crosses endTime, so it always
                    // includes some trains past endTime (and of every type) by construction.
                    // matchingTrains is what the user actually asked for; report that count, not
                    // the raw fetch count, so the log reflects their dep/arr/time/type criteria.
                    val matchingTrains = trains.filter { t ->
                        (endTime.isBlank() || t.depTime <= endTime) &&
                            allowedTypes.any { it.matches(t.trainTypeName) }
                    }
                    val candidates = matchingTrains.filter { t ->
                        if (prefs.seatType == KorailApi.SEAT_SPECIAL) t.hasSpecialSeat else t.hasGeneralSeat
                    }

                    if (candidates.isNotEmpty()) {
                        val target = candidates.first()
                        ServiceBus.append("빈 좌석 발견 -> 예약 시도: ${target.summary()}")
                        try {
                            val pnr = api.reserve(target, prefs.adultCount, prefs.seatType)
                            val msg = "✅ 예약 성공!\n${target.summary()}\n예약번호(PNR): $pnr\n\n결제 마감 시간 내에 코레일 앱/사이트에서 직접 결제를 완료하세요."
                            ServiceBus.append(msg)
                            notifier.send(msg)
                            updateNotification(buildResultNotification("예약 성공: $pnr"))
                            stopSelfSafely()
                            return@launch
                        } catch (e: Exception) {
                            ServiceBus.append("예약 시도 실패: ${e.message}")
                            if (isSessionExpired(e.message)) reLogin(api, prefs)
                        }
                    } else {
                        ServiceBus.append("빈 좌석 없음 (조건에 맞는 열차 ${matchingTrains.size}개 확인, ${intervalMs / 1000}초 후 재시도)")
                    }
                } catch (e: Exception) {
                    ServiceBus.append("조회 오류: ${e.message}")
                    if (isSessionExpired(e.message)) reLogin(api, prefs)
                }
                updateNotification(buildOngoingNotification("계속 조회 중... (${intervalMs / 1000}초 간격)"))
                delay(intervalMs)
            }
        }
    }

    /**
     * A lost reservation race can invalidate the session server-side, not just fail that one
     * request — every subsequent search/reserve call then fails with the same "로그아웃" message
     * forever unless we notice and log back in. This was previously mislabeled as "선점됨" (seat
     * taken by someone else), which isn't what a session-expired error actually means.
     */
    private fun isSessionExpired(message: String?): Boolean =
        message != null && ("로그아웃" in message || "다시 로그인" in message)

    private fun reLogin(api: KorailApi, prefs: Prefs) {
        try {
            api.login(prefs.loginType, prefs.loginId, prefs.password)
            ServiceBus.append("세션 만료 감지 -> 재로그인 성공")
        } catch (e: Exception) {
            ServiceBus.append("재로그인 실패: ${e.message}")
        }
    }

    private fun stopSelfSafely() {
        job?.cancel()
        ServiceBus.setRunning(false)
        ServiceBus.append("서비스 중지")
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        job?.cancel()
        ServiceBus.setRunning(false)
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "예매 매크로 실행 상태", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, "예매 결과 알림", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun stopPendingIntent(): PendingIntent {
        val stopIntent = Intent(this, ReservationService::class.java).apply { action = ACTION_STOP }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(this, 0, stopIntent, flags)
    }

    private fun contentPendingIntent(): PendingIntent {
        val openIntent = Intent(this, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 0, openIntent, flags)
    }

    private fun buildOngoingNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setContentTitle("코레일 예매 매크로 실행 중")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setOngoing(true)
            .setContentIntent(contentPendingIntent())
            .addAction(android.R.drawable.ic_media_pause, "중지", stopPendingIntent())
            .build()

    private fun buildResultNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_RESULT)
            .setContentTitle("코레일 예매 결과")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent())
            .build()

    private fun updateNotification(notification: Notification) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, notification)
    }

    companion object {
        const val ACTION_STOP = "com.korailmacro.app.STOP"
        const val CHANNEL_ONGOING = "korail_macro_ongoing"
        const val CHANNEL_RESULT = "korail_macro_result"
        const val NOTIF_ID = 1001
    }
}
