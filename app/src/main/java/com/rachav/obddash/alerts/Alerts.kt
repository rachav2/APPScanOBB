package com.rachav.obddash.alerts

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.rachav.obddash.MainActivity
import com.rachav.obddash.data.AlertAction
import com.rachav.obddash.data.AlertRule
import com.rachav.obddash.data.AppPrefs
import java.util.Locale

/** Ejecuta las acciones de una alerta: sonido, vibración, notificación y voz. Todo con try/catch: una alerta nunca debe tumbar la app. */
class AlertNotifier(context: Context) {
    private val app = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        ensureChannels(app)
    }

    fun fire(rule: AlertRule, valueText: String) {
        val msg = "${rule.name}: $valueText"
        if (AlertAction.Sound in rule.actions) runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_ALARM, 90)
            tone.startTone(if (rule.critical) ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD else ToneGenerator.TONE_PROP_BEEP2, if (rule.critical) 1200 else 400)
        }
        if (AlertAction.Vibrate in rule.actions) runCatching {
            @Suppress("DEPRECATION")
            val v = app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (v != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(longArrayOf(0, 300, 150, 300), -1)
                }
            }
        }
        if (AlertAction.Notify in rule.actions) runCatching {
            val allowed = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (allowed) {
                val n = NotificationCompat.Builder(app, CH_ALERTS)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(if (rule.critical) "Alerta crítica" else "Alerta")
                    .setContentText(msg)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(openAppIntent(app))
                    .build()
                (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(rule.id.hashCode(), n)
            }
        }
        if (AlertAction.Voice in rule.actions) runCatching { speak(msg) }
    }

    private fun speak(text: String) {
        val t = tts
        if (t == null) {
            tts = TextToSpeech(app) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.language = Locale("es")
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "alert")
                }
            }
        } else if (ttsReady) {
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "alert")
        }
    }

    fun shutdown() {
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
    }

    companion object {
        const val CH_ALERTS = "alerts"
        const val CH_MONITOR = "monitor"

        fun ensureChannels(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CH_ALERTS, "Alertas del auto", NotificationManager.IMPORTANCE_HIGH))
            nm.createNotificationChannel(NotificationChannel(CH_MONITOR, "Monitoreo en segundo plano", NotificationManager.IMPORTANCE_LOW))
        }

        fun openAppIntent(ctx: Context): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            return PendingIntent.getActivity(ctx, 0, i, flags)
        }
    }
}

/**
 * Servicio en primer plano: mantiene vivo el proceso (y con él la conexión y las alertas)
 * cuando la app pasa a segundo plano. Muestra una notificación permanente.
 */
class MonitorService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AlertNotifier.ensureChannels(this)
        val n: Notification = NotificationCompat.Builder(this, AlertNotifier.CH_MONITOR)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("OBD Dash")
            .setContentText("Monitoreando el auto y vigilando las alertas")
            .setOngoing(true)
            .setContentIntent(AlertNotifier.openAppIntent(this))
            .build()
        try {
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
            ServiceCompat.startForeground(this, 1, n, type)
        } catch (e: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, MonitorService::class.java)) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, MonitorService::class.java)) }
        }
    }
}

/** Abre la app al encender el sistema, si el usuario lo activó. Algunos Android de auto bloquean este permiso. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!AppPrefs(context).autostart) return
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
