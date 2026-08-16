package com.korailmacro.app

import android.content.Context
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.korailmacro.app.korail.Train
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** 스와이프로 오가는 조회수 전용 화면: ReservationService의 가장 최근 검색 결과(필터 적용 전 전체 열차)를 실시간으로 보여준다. */
class SeatStatusActivity : AppCompatActivity() {

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
        val prefs = Prefs(this)

        val listTrains = findViewById<ListView>(R.id.listTrains)
        val textEmpty = findViewById<TextView>(R.id.textEmpty)
        val textRouteSummary = findViewById<TextView>(R.id.textRouteSummary)
        val textLastUpdated = findViewById<TextView>(R.id.textLastUpdated)

        textRouteSummary.text = "${prefs.depStation} → ${prefs.arrStation} · ${prefs.travelDate}"

        val adapter = TrainStatusAdapter(this)
        listTrains.adapter = adapter

        lifecycleScope.launch {
            ServiceBus.trains.collect { trains ->
                adapter.replaceAll(trains)
                textEmpty.visibility = if (trains.isEmpty()) View.VISIBLE else View.GONE
            }
        }
        lifecycleScope.launch {
            ServiceBus.lastSearchedAt.collect { timestamp ->
                textLastUpdated.text = if (timestamp == 0L) "" else
                    "마지막 조회: " + SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(timestamp))
            }
        }
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
