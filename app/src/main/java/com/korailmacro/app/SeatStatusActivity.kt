package com.korailmacro.app

import android.content.Context
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.korailmacro.app.korail.KorailApi
import com.korailmacro.app.korail.Train
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 스와이프로 오가는 조회 전용 화면. ReservationService(매크로)와는 완전히 독립적으로,
 * 이 화면 자체가 로그인+조회를 직접 수행한다 — 매크로를 시작하지 않고도, 매크로가 실행
 * 중이든 아니든 상관없이 언제든 "지금 좌석이 있는지"만 바로 확인할 수 있다. 예약(reserve)은
 * 절대 호출하지 않는다.
 */
class SeatStatusActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var textStatus: TextView
    private lateinit var textLastUpdated: TextView
    private lateinit var buttonRefresh: Button
    private lateinit var listTrains: ListView
    private lateinit var adapter: TrainStatusAdapter

    private val gestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (dx < -150 && abs(dx) > abs(dy) && abs(velocityX) > 300) {
                    finish()
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
        setContentView(R.layout.activity_seat_status)
        prefs = Prefs(this)

        listTrains = findViewById(R.id.listTrains)
        textStatus = findViewById(R.id.textStatus)
        textLastUpdated = findViewById(R.id.textLastUpdated)
        buttonRefresh = findViewById(R.id.buttonRefresh)
        val textRouteSummary = findViewById<TextView>(R.id.textRouteSummary)

        textRouteSummary.text = "${prefs.depStation} → ${prefs.arrStation} · ${prefs.travelDate}"

        adapter = TrainStatusAdapter(this)
        listTrains.adapter = adapter

        buttonRefresh.setOnClickListener { runQuery() }

        runQuery()
    }

    /** 이 화면 안에서만 쓰는 독립적인 1회성 로그인+조회. 매크로 실행 상태와 무관하게 매번 새로 조회한다. */
    private fun runQuery() {
        if (prefs.loginId.isBlank() || prefs.password.isBlank()) {
            showStatus("환경설정(⚙)에서 코레일 로그인 정보를 입력하세요")
            return
        }
        if (prefs.depStation.isBlank() || prefs.arrStation.isBlank() || prefs.travelDate.isBlank()) {
            showStatus("출발역/도착역/날짜를 먼저 선택하세요")
            return
        }

        buttonRefresh.isEnabled = false
        listTrains.visibility = View.GONE
        showStatus("조회 중...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val api = KorailApi()
                api.login(prefs.loginType, prefs.loginId, prefs.password)
                val allTrains = api.searchTrain(
                    prefs.depStation, prefs.arrStation,
                    prefs.travelDate, prefs.startTime, prefs.adultCount, prefs.endTime
                )
                // 메인 화면의 열차 종류·종료시각 조건과 동일하게 걸러서 보여준다 (KTX만 체크했는데
                // ITX가 같이 보이거나, 종료시각을 넘긴 열차까지 보이던 문제 수정). searchTrain은
                // 페이지 단위로 가져오다 보니 원시 결과 자체는 종료시각을 넘긴 열차도 포함한다.
                val allowedTypes = prefs.trainTypes
                val endTime = prefs.endTime
                val trains = allTrains.filter { t ->
                    allowedTypes.any { it.matches(t.trainTypeName) } &&
                        (endTime.isBlank() || t.depTime <= endTime)
                }
                runOnUiThread {
                    adapter.replaceAll(trains)
                    if (trains.isEmpty()) {
                        showStatus("조건에 맞는 열차가 없습니다")
                    } else {
                        textStatus.visibility = View.GONE
                        listTrains.visibility = View.VISIBLE
                    }
                    textLastUpdated.text = "마지막 조회: " + SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
                }
            } catch (e: Exception) {
                runOnUiThread { showStatus("조회 실패: ${e.message}") }
            } finally {
                runOnUiThread { buttonRefresh.isEnabled = true }
            }
        }
    }

    private fun showStatus(message: String) {
        textStatus.text = message
        textStatus.visibility = View.VISIBLE
    }

    private class TrainStatusAdapter(context: Context) : ArrayAdapter<Train>(context, 0) {

        fun replaceAll(newItems: List<Train>) {
            clear()
            addAll(newItems)
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_train_status, parent, false)
            val train = getItem(position) ?: return view

            view.findViewById<TextView>(R.id.textTrainTitle).text = "[${train.trainTypeName} ${train.trainNo}]"
            view.findViewById<TextView>(R.id.textTrainRoute).text =
                "${train.depStationName} ${formatTime(train.depTime)} → ${train.arrStationName} ${formatTime(train.arrTime)}"

            val generalView = view.findViewById<TextView>(R.id.textGeneralSeat)
            generalView.text = "일반실: ${if (train.hasGeneralSeat) "가능" else "매진"}"
            generalView.setTextColor(
                ContextCompat.getColor(context, if (train.hasGeneralSeat) R.color.colorSuccess else R.color.textSecondary)
            )

            val specialView = view.findViewById<TextView>(R.id.textSpecialSeat)
            specialView.text = "특실: ${if (train.hasSpecialSeat) "가능" else "매진"}"
            specialView.setTextColor(
                ContextCompat.getColor(context, if (train.hasSpecialSeat) R.color.colorSuccess else R.color.textSecondary)
            )

            return view
        }

        private fun formatTime(raw: String): String =
            if (raw.length >= 4) "${raw.substring(0, 2)}:${raw.substring(2, 4)}" else raw
    }
}
