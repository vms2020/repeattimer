package io.github.vms2020.repeattimer

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class TimerService : Service() {

    companion object {
        const val ACTION_START = "com.example.countdowntimer.START"
        const val ACTION_STOP_ALARM = "com.example.countdowntimer.STOP_ALARM"
        const val ACTION_STOP_ALL = "com.example.countdowntimer.STOP_ALL"
        const val ACTION_TIMER_FIRED = "com.example.countdowntimer.TIMER_FIRED"
        const val CHANNEL_ID = "timer_channel"
        const val NOTIF_ID = 1001
        const val ALARM_REQUEST_CODE = 2001

        fun send(context: Context, action: String) {
            val i = Intent(context, TimerService::class.java).setAction(action)
            if (action == ACTION_START) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    context.startForegroundService(i)
                else
                    context.startService(i)
            } else {
                // Стоп-команды приходят только когда сервис уже запущен,
                // поэтому обычный startService безопасен.
                context.startService(i)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var timerJob: Job? = null
    private var player: MediaPlayer? = null
    private lateinit var prefs: SharedPreferences
    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val s = TimerStateHolder.state.value
        when (intent?.action) {
            ACTION_START -> startSeries()

            ACTION_TIMER_FIRED -> {
                // Сервис разбужен будильником — начинаем играть сигнал
                onTimerFired()
            }

            ACTION_STOP_ALARM -> {
                if (s.isAlarmPlaying || s.isRunning) stopAlarm() else stopSelf()
            }

            ACTION_STOP_ALL -> {
                if (s.isRunning || s.isAlarmPlaying) stopAll() else stopSelf()
            }

            else -> stopSelf()
        }
        return START_NOT_STICKY
    }


    private fun startSeries() {
        val interval = prefs.getInt("interval", 15)
        val count = prefs.getInt("count", 4)

        releasePlayer()
        TimerStateHolder.update {
            it.copy(
                intervalMinutes = interval,
                totalIntervals = count,
                remainingIntervals = count,
                secondsLeft = interval * 60,
                isRunning = true,
                isAlarmPlaying = false
            )
        }
        startFg()
        scheduleAlarm()
        runTimer()
    }

    private fun scheduleAlarm() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intervalMs = TimerStateHolder.state.value.intervalMinutes * 60_000L
        val triggerAt =
            SystemClock.elapsedRealtime() + intervalMs   // elapsedRealtime не сдвигается переводом часов

        val intent = Intent(this, AlarmReceiver::class.java).apply {
            action = ACTION_TIMER_FIRED
        }
        val pi = PendingIntent.getBroadcast(
            this,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+
            if (!am.canScheduleExactAlarms()) {
                // Разрешение отозвано. Fallback — неточный будильник (сработает примерно).
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                return
            }
        }

        // ELAPSED_REALTIME_WAKEUP будит устройство и использует монотонные часы — не боится перевода времени.
        am.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerAt,
            pi
        )
    }

    private fun cancelAlarm() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java).apply {
            action = ACTION_TIMER_FIRED
        }
        val pi = PendingIntent.getBroadcast(
            this,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
    }

    private fun onTimerFired() {
        val s = TimerStateHolder.state.value
        if (!s.isRunning || s.isAlarmPlaying) return  // игнорируем лишние срабатывания

        timerJob?.cancel()
        timerJob = null

        TimerStateHolder.update { it.copy(secondsLeft = 0, isAlarmPlaying = true) }
        updateNotification()
        playAlarm2()
    }

    private fun runTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            val intervalMs = TimerStateHolder.state.value.intervalMinutes * 60_000L
            val deadline = SystemClock.elapsedRealtime() + intervalMs

            while (isActive) {
                val remainingMs = deadline - SystemClock.elapsedRealtime()
                val remainingSec = ((remainingMs + 999) / 1000L).toInt().coerceAtLeast(0)

                if (remainingSec != TimerStateHolder.state.value.secondsLeft) {
                    TimerStateHolder.update { it.copy(secondsLeft = remainingSec) }
                    updateNotification()
                }

                if (remainingSec <= 0) {
                    // Если будильник уже сработал раньше — просто выходим
                    if (!TimerStateHolder.state.value.isAlarmPlaying) {
                        onTimerFired()
                    }
                    return@launch
                }

                delay(500.milliseconds)
            }
        }
    }

    private fun stopAlarm() {
        releasePlayer()
        TimerStateHolder.update { it.copy(isAlarmPlaying = false) }

        val s = TimerStateHolder.state.value
        if (!s.isRunning) {
            updateNotification()
            return
        }

        val remaining = s.remainingIntervals - 1
        if (remaining > 0) {
            TimerStateHolder.update {
                it.copy(
                    remainingIntervals = remaining,
                    secondsLeft = s.intervalMinutes * 60
                )
            }
            updateNotification()
            scheduleAlarm()
            runTimer()
        } else {
            // Последний сигнал — завершаем серию
            stopAll()
        }
    }

    private fun stopAll() {
        timerJob?.cancel()
        timerJob = null
        releasePlayer()
        cancelAlarm()

        val interval = prefs.getInt("interval", 15)
        val count = prefs.getInt("count", 4)
        TimerStateHolder.update {
            it.copy(
                intervalMinutes = interval,
                totalIntervals = count,
                remainingIntervals = count,
                secondsLeft = interval * 60,
                isRunning = false,
                isAlarmPlaying = false
            )
        }
        stopFg()
        stopSelf()
    }

    private fun playAlarm() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@TimerService, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) { /* без звука — не критично */
        }
    }

    fun playAlarm2() {
        releasePlayer()
        val customUri = loadSelectedSoundUri(this)
        val uri = customUri
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@TimerService, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {
            // Если пользовательский файл недоступен — падаем на системный
            //playAlarm()
        }
    }

    private fun releasePlayer() {
        player?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        player = null
    }

    // ---------------- foreground + нотификация ----------------

    private fun startFg() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }

//        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
//            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
//        //ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
//
//        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
    }

    private fun stopFg() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Таймер",
                NotificationManager.IMPORTANCE_DEFAULT  // IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                description = "Отсчёт интервалов"
                enableVibration(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val s = TimerStateHolder.state.value

        val openIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(titleFor(s))
            .setContentText(textFor(s))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (s.isAlarmPlaying) {
            builder.addAction(
                NotificationCompat.Action.Builder(
                    0,
                    //"Стоп сигнал",
                    getString(R.string.stop_signal),
                    actionPi(ACTION_STOP_ALARM, 1)
                ).build()
            )
        }
        builder.addAction(
            NotificationCompat.Action.Builder(
                0,
                getString(R.string.stop_seria_lc),//"Стоп серия",
                actionPi(ACTION_STOP_ALL, 2)
            ).build()
        )

        return builder.build()
    }

    private fun actionPi(action: String, req: Int): PendingIntent {
        val i = Intent(this, TimerService::class.java).setAction(action)
        return PendingIntent.getService(
            this, req, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

//    private fun titleFor(s: TimerState): String {
//        if (!s.isRunning) return "Таймер остановлен"
//        val idx = (s.totalIntervals - s.remainingIntervals + 1).coerceAtLeast(1)
//        return "Интервал $idx из ${s.totalIntervals}"
//    }
//
//    private fun textFor(s: TimerState): String {
//        if (!s.isRunning) return "Серия не запущена"
//        if (s.isAlarmPlaying) {
//            return "🔔 Сигнал! Осталось интервалов после: ${s.remainingIntervals - 1}"
//        }
//        val time = "%02d:%02d".format(s.secondsLeft / 60, s.secondsLeft % 60)
//        return "До сигнала: $time · Осталось: ${s.remainingIntervals}"
//    }

    private fun titleFor(s: TimerState): String {
        if (!s.isRunning) return getString(R.string.timer_stopped)
        val idx = (s.totalIntervals - s.remainingIntervals + 1).coerceAtLeast(1)
        return getString(R.string.interval_of, idx, s.totalIntervals)
    }

    private fun textFor(s: TimerState): String {
        if (!s.isRunning) return getString(R.string.series_not_started)
        if (s.isAlarmPlaying) {
            return getString(R.string.alarm_playing, s.remainingIntervals - 1)
        }
        val time = "%02d:%02d".format(s.secondsLeft / 60, s.secondsLeft % 60)
        return getString(R.string.until_signal_details, time, s.remainingIntervals)
    }

    private fun updateNotification() {
        try {
            nm.notify(NOTIF_ID, buildNotification())
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        timerJob?.cancel()
        releasePlayer()
        scope.cancel()
    }
}
