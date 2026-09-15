package io.github.vms2020.repeattimer

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import kotlin.time.Duration.Companion.milliseconds

class TimerService : Service() {

    companion object {
        const val ACTION_START = "com.example.countdowntimer.START"
        const val ACTION_STOP_ALARM = "com.example.countdowntimer.STOP_ALARM"
        const val ACTION_STOP_ALL = "com.example.countdowntimer.STOP_ALL"
        const val CHANNEL_ID = "timer_channel"
        const val NOTIF_ID = 1001

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

    // ---------------- логика серии ----------------

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
        runTimer()
    }

    private fun runTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive) {
                delay(1000.milliseconds)
                val s = TimerStateHolder.state.value
                if (!s.isRunning) return@launch

                if (s.secondsLeft > 1) {
                    TimerStateHolder.update { it.copy(secondsLeft = it.secondsLeft - 1) }
                    updateNotification()
                } else {
                    // Время вышло — играем сигнал и ждём пользователя
                    TimerStateHolder.update {
                        it.copy(secondsLeft = 0, isAlarmPlaying = true)
                    }
                    updateNotification()
                    playAlarm2()
                    return@launch
                }
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

    // ---------------- звук ----------------

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
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
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
                    0, "Стоп сигнал", actionPi(ACTION_STOP_ALARM, 1)
                ).build()
            )
        }
        builder.addAction(
            NotificationCompat.Action.Builder(
                0, "Стоп серия", actionPi(ACTION_STOP_ALL, 2)
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

    private fun titleFor(s: TimerState): String {
        if (!s.isRunning) return "Таймер остановлен"
        val idx = (s.totalIntervals - s.remainingIntervals + 1).coerceAtLeast(1)
        return "Интервал $idx из ${s.totalIntervals}"
    }

    private fun textFor(s: TimerState): String {
        if (!s.isRunning) return "Серия не запущена"
        if (s.isAlarmPlaying) {
            return "🔔 Сигнал! Осталось интервалов после: ${s.remainingIntervals - 1}"
        }
        val time = "%02d:%02d".format(s.secondsLeft / 60, s.secondsLeft % 60)
        return "До сигнала: $time · Осталось: ${s.remainingIntervals}"
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
