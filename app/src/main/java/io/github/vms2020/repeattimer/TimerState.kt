package io.github.vms2020.repeattimer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class TimerState(
    val intervalMinutes: Int = 15,
    val totalIntervals: Int = 4,
    val remainingIntervals: Int = 4,
    val secondsLeft: Int = 15 * 60,
    val isRunning: Boolean = false,
    val isAlarmPlaying: Boolean = false
)


object TimerStateHolder {
    private val _state = MutableStateFlow(TimerState())
    val state: StateFlow<TimerState> = _state.asStateFlow()
    fun update(block: (TimerState) -> TimerState) = _state.update(block)
}
