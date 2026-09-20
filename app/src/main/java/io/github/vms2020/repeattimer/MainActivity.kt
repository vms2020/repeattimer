package io.github.vms2020.repeattimer

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import io.github.vms2020.repeattimer.ui.theme.RepeatTimerTheme
import androidx.core.content.edit
import androidx.core.net.toUri

const val PREFS_NAME = "timer_prefs"
const val KEY_SOUND_URI = "selected_sound_uri"
const val KEY_SOUND_NAME = "selected_sound_name"

class MainActivity : ComponentActivity() {

    private fun ensureExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!am.canScheduleExactAlarms()) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ensureExactAlarmPermission()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }

        loadSavedSettingsIntoState()

        setContent {
            RepeatTimerTheme {
                Surface(
                    Modifier
                        .fillMaxSize(),

//                        .safeDrawingPadding()
                ) {
                    Screen()
                }
            }
        }
    }

    private fun loadSavedSettingsIntoState() {
        val current = TimerStateHolder.state.value
        // Если серия уже идёт — состояние уже актуально, не перезаписываем
        if (current.isRunning || current.isAlarmPlaying) return

        val prefs = getSharedPreferences("timer_prefs", MODE_PRIVATE)
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
    }
}


@Composable
fun Screen() {
    val context = LocalContext.current
    val state by TimerStateHolder.state.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    var soundUri by rememberSaveable { mutableStateOf(loadSelectedSoundUri(context)) }
    val soundName = remember(soundUri) { loadSelectedSoundName(context) }

    val intervalSec = state.intervalMinutes * 60
    val progress = if (intervalSec > 0) {
        1f - (state.secondsLeft.toFloat() / intervalSec.toFloat())
    } else 0f

    val currentIdx = (state.totalIntervals - state.remainingIntervals + 1)
        .coerceIn(1, state.totalIntervals)

    val ringtonePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val selectedUri: Uri? = result.data?.let { data ->
                IntentCompat.getParcelableExtra(
                    data,
                    RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
                    Uri::class.java
                )
            }
            if (selectedUri != null) {
                val name = RingtoneManager.getRingtone(context, selectedUri)?.getTitle(context)
                saveSelectedSoundUri(context, selectedUri, name)
                soundUri = selectedUri
            }
        }
    }
    val progressColor = when {
        state.isAlarmPlaying -> colorScheme.error
        progress > 0.9f -> colorScheme.error.copy(alpha = 0.7f)
        progress > 0.75f -> colorScheme.tertiary
        else -> colorScheme.primary
    }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(R.string.timer_title),
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(Modifier.height(24.dp))

        CircularTimer(
            progress = progress,
            progressColor = progressColor,
            size = 280.dp,
            strokeWidth = 16.dp,
//            progressColor = if (state.isAlarmPlaying)
//                MaterialTheme.colorScheme.error
//            else
//                MaterialTheme.colorScheme.primary
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = formatTime(state.secondsLeft),
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Bold,
                    style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum")
                )
                Text(
                    stringResource(R.string.next_signal),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.interval_of, currentIdx, state.totalIntervals),
                    //"Интервал $currentIdx из ${state.totalIntervals}",
                    fontSize = 18.sp
                )
                Text(
                    stringResource(R.string.intervals_left, state.remainingIntervals),
                    //"Осталось интервалов: ${state.remainingIntervals}",
                    fontSize = 16.sp
                )
            }
        }
//        Text(
//            text = formatTime(state.secondsLeft),
//            fontSize = 72.sp,
//            fontWeight = FontWeight.Bold,
//            style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
//        )
//        Text(stringResource(R.string.next_signal), fontSize = 14.sp)

//        Spacer(Modifier.height(16.dp))


        Spacer(Modifier.height(24.dp))

        when {
            state.isAlarmPlaying -> {
                Button(
                    onClick = { TimerService.send(context, TimerService.ACTION_STOP_ALARM) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(
                        stringResource(R.string.stop_alarm),
                        //"ОСТАНОВИТЬ СИГНАЛ",
                        fontSize = 20.sp
                    )
                }
            }

            state.isRunning -> {
                OutlinedButton(
                    onClick = { },
                    enabled = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                ) {
                    Text(
                        stringResource(R.string.counting),
                        //"ИДЁТ ОТСЧЁТ…",
                        fontSize = 20.sp
                    )
                }
            }

            else -> {
                Button(
                    onClick = { TimerService.send(context, TimerService.ACTION_START) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                ) { Text(stringResource(R.string.start_series), fontSize = 20.sp) }
            }
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = { TimerService.send(context, TimerService.ACTION_STOP_ALL) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = state.isRunning || state.isAlarmPlaying
        ) {
            Text(
                stringResource(R.string.stop_series),
                //"ОСТАНОВИТЬ ВСЮ СЕРИЮ",
                fontSize = 16.sp
            )
        }

        Spacer(Modifier.weight(1f))

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(
                onClick = { showSettings = true },
                enabled = !state.isRunning && !state.isAlarmPlaying
            ) {
                Text(
                    stringResource(R.string.settings)
                    //    "Настройки"
                )
            }
            Row(
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    soundName ?: stringResource(R.string.default_sound),  //"Звук по умолчанию",
                    style = MaterialTheme.typography.labelSmall,
                )
                val chooseSoundTitle = stringResource(R.string.choose_sound)
                FilledIconButton(
                    {
                        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(
                                RingtoneManager.EXTRA_RINGTONE_TYPE,
                                RingtoneManager.TYPE_ALARM
                            )
                            putExtra(
                                RingtoneManager.EXTRA_RINGTONE_TITLE,
                                chooseSoundTitle,
                                //"Выберите звук для таймера"
                            )
                            loadSelectedSoundUri(context)?.let {
                                putExtra(
                                    RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                    it
                                )
                            }
                        }
                        ringtonePickerLauncher.launch(intent)
                    },
                    enabled = !state.isRunning && !state.isAlarmPlaying
                ) {
                    Icon(
                        Icons.Default.Audiotrack,
                        stringResource(R.string.choose_sound)
                        //    "RingTone"
                    )
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            intervalMinutes = state.intervalMinutes,
            totalIntervals = state.totalIntervals,
            onIntervalChange = { v ->
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putInt("interval", v).apply()
                TimerStateHolder.update {
                    it.copy(intervalMinutes = v, secondsLeft = v * 60)
                }
            },
            onCountChange = { v ->
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putInt("count", v).apply()
                TimerStateHolder.update {
                    it.copy(totalIntervals = v, remainingIntervals = v)
                }
            },
            onDismiss = { showSettings = false }
        )
    }
}

fun loadSelectedSoundUri(context: Context): Uri? {
    val str = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_SOUND_URI, null)
    return str?.toUri()
}

fun saveSelectedSoundUri(context: Context, uri: Uri, displayName: String?) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit {
            putString(KEY_SOUND_URI, uri.toString())
            putString(KEY_SOUND_NAME, displayName)
        }
}

fun loadSelectedSoundName(context: Context): String? {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_SOUND_NAME, null)
}

private fun formatTime(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%02d:%02d".format(m, s)
}

@Composable
fun SettingsDialog(
    intervalMinutes: Int,
    totalIntervals: Int,
    onIntervalChange: (Int) -> Unit,
    onCountChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var intervalText by remember { mutableStateOf(intervalMinutes.toString()) }
    var countText by remember { mutableStateOf(totalIntervals.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.settings)
                //    "Настройки"
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = intervalText,
                    onValueChange = { intervalText = it.filter(Char::isDigit).take(4) },
                    label = {
                        Text(
                            stringResource(R.string.interval_minutes)
                            //    "Интервал, минут"
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = countText,
                    onValueChange = { countText = it.filter(Char::isDigit).take(2) },
                    label = {
                        Text(
                            stringResource(R.string.interval_count)
                            //"Количество интервалов"
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                intervalText.toIntOrNull()?.let { onIntervalChange(it.coerceIn(1, 999)) }
                countText.toIntOrNull()?.let { onCountChange(it.coerceIn(1, 99)) }
                onDismiss()
            }) {
                Text(
                    stringResource(R.string.save)
                    //    "Сохранить"
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

