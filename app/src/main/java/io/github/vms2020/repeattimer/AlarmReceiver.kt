package io.github.vms2020.repeattimer


import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TimerService.send(context, TimerService.ACTION_TIMER_FIRED)
    }
}