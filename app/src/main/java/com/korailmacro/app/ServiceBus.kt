package com.korailmacro.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** In-process log/status bridge between ReservationService and MainActivity. */
object ServiceBus {
    private val lines = ArrayDeque<String>()

    private val _log = MutableStateFlow("대기 중...")
    val log: StateFlow<String> = _log

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    @Synchronized
    fun append(line: String) {
        lines.addLast(line)
        while (lines.size > 300) lines.removeFirst()
        _log.value = lines.joinToString("\n")
    }

    fun setRunning(value: Boolean) {
        _running.value = value
    }

    @Synchronized
    fun clear() {
        lines.clear()
        _log.value = "대기 중..."
    }
}
