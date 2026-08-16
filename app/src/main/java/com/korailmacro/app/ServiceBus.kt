package com.korailmacro.app

import com.korailmacro.app.korail.Train
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** In-process log/status bridge between ReservationService and MainActivity/SeatStatusActivity. */
object ServiceBus {
    private val lines = ArrayDeque<String>()

    private val _log = MutableStateFlow("대기 중...")
    val log: StateFlow<String> = _log

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    /** Full (unfiltered) result of the most recent search, for the 좌석 현황 screen. */
    private val _trains = MutableStateFlow<List<Train>>(emptyList())
    val trains: StateFlow<List<Train>> = _trains

    private val _lastSearchedAt = MutableStateFlow(0L)
    val lastSearchedAt: StateFlow<Long> = _lastSearchedAt

    @Synchronized
    fun append(line: String) {
        lines.addLast(line)
        while (lines.size > 300) lines.removeFirst()
        _log.value = lines.joinToString("\n")
    }

    fun setRunning(value: Boolean) {
        _running.value = value
    }

    fun updateTrains(list: List<Train>) {
        _trains.value = list
        _lastSearchedAt.value = System.currentTimeMillis()
    }

    @Synchronized
    fun clear() {
        lines.clear()
        _log.value = "대기 중..."
    }
}
