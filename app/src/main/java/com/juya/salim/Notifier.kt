package com.juya.salim

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 매일 정한 시간(기본 아침 9시)에 한 번 깨어나서
 * 화면에서 넘겨받은 알림 목록(sched) 중 오늘 알릴 것들을 모아 알림 1개로 보여줌.
 * sched 항목: {"n":이름, "k":"buy"|"spoil"|"low", "d":"기준 날짜", "on":"알리기 시작할 날짜"}
 */
object Notifier {
    // 소리는 앱이 직접 냄 (볼륨·알람음·방해금지 설정 때문에) → 알림 자체는 조용한 채널
    private const val CH = "daily_quiet"
    private const val NOTI_ID = 1

    fun pt(c: Context) = prefs(c).getString("lang", "ko") == "pt"

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("salim", Context.MODE_PRIVATE)

    fun ensureChannel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) {
            val ch = NotificationChannel(CH, "집 도우미 / Ajudante da Casa", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "다시 살 물건, 먼저 먹을 재료 알림"
            ch.setSound(null, null)
            ch.enableVibration(false)
            nm.createNotificationChannel(ch)
        }
    }

    private fun alarmIntent(c: Context): PendingIntent =
        PendingIntent.getBroadcast(c, 0, Intent(c, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** 다음 알림 시간 맞추기 (정확한 알람 권한 없이도 되는 방식 — 몇 분 늦을 수 있음) */
    fun scheduleNext(c: Context) {
        val hour = prefs(c).getInt("hour", 9)
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(hour, 0)
        if (!next.isAfter(now.plusMinutes(1))) next = next.plusDays(1)
        val ms = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val am = c.getSystemService(AlarmManager::class.java)
        am.cancel(alarmIntent(c))
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, alarmIntent(c))
    }

    /** 오늘 알릴 것들의 문장 목록 (급한 것 먼저) */
    fun dueLines(c: Context): List<String> {
        val raw = prefs(c).getString("sched", "") ?: ""
        if (raw.isBlank()) return emptyList()
        val today = LocalDate.now()
        val pt = pt(c)
        val urgent = mutableListOf<String>()
        val soon = mutableListOf<String>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val on = LocalDate.parse(o.optString("on"))
                if (today.isBefore(on)) continue
                val name = o.optString("n")
                val d = try { ChronoUnit.DAYS.between(today, LocalDate.parse(o.optString("d"))) } catch (e: Exception) { 0L }
                when (o.optString("k")) {
                    "buy" -> if (d <= 0) urgent.add(if (pt) "🧻 $name — compre agora!" else "🧻 $name — 지금 사야 해요!")
                             else soon.add(if (pt) "🧻 $name — comprar em $d dia(s)" else "🧻 $name — ${d}일 후 다시 사세요")
                    "spoil" -> when {
                        d < -3 -> {}  // 너무 지난 건 그만 알림
                        d < 0 -> urgent.add(if (pt) "⚠️ $name — pode ter estragado, confira" else "⚠️ $name — 상했을 수 있어요, 확인하세요")
                        d == 0L -> urgent.add(if (pt) "🥬 $name — consuma hoje" else "🥬 $name — 오늘까지 드세요")
                        else -> soon.add(if (pt) "🥬 $name — consuma em $d dia(s)" else "🥬 $name — ${d}일 안에 드세요")
                    }
                    "low" -> soon.add(if (pt) "🛒 $name — está acabando" else "🛒 $name — 거의 다 먹었어요")
                }
            }
        } catch (e: Exception) { }
        return urgent + soon
    }

    /** 알림 띄우기. 띄웠으면 true */
    fun fire(c: Context, force: Boolean = false): Boolean {
        ensureChannel(c)
        val lines = dueLines(c)
        if (lines.isEmpty()) return false
        val open = PendingIntent.getActivity(c, 1, Intent(c, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val style = Notification.InboxStyle()
        lines.take(7).forEach { style.addLine(it) }
        val pt = pt(c)
        if (lines.size > 7) style.setSummaryText(if (pt) "+${lines.size - 7}" else "외 ${lines.size - 7}개 더")
        val n = Notification.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(0xFF3F8F5A.toInt())
            .setContentTitle(if (force) (if (pt) "Aviso (teste)" else "집 도우미 (시험)")
                else (if (pt) "Hoje: ${lines.size} item(ns) para ver" else "오늘 챙길 것 ${lines.size}개"))
            .setContentText(lines.first())
            .setStyle(style)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        return try {
            c.getSystemService(NotificationManager::class.java).notify(NOTI_ID, n)
            ring(c, force)
            true
        } catch (e: SecurityException) { false }
    }

    // ---------- 알람 소리 ----------
    private fun alarm(c: Context): JSONObject =
        try { JSONObject(prefs(c).getString("alarm", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    fun soundUri(c: Context): Uri =
        prefs(c).getString("soundUri", null)?.let { Uri.parse(it) } ?: Settings.System.DEFAULT_NOTIFICATION_URI

    fun soundTitle(c: Context): String = try {
        RingtoneManager.getRingtone(c, soundUri(c))?.getTitle(c) ?: ""
    } catch (e: Exception) { "" }

    private fun inQuiet(a: JSONObject): Boolean {
        if (!a.optBoolean("quietOn", true)) return false
        val s = a.optInt("quietStart", 22); val e = a.optInt("quietEnd", 7)
        val h = LocalTime.now().hour
        return if (s == e) false else if (s < e) h in s until e else (h >= s || h < e)
    }

    private var player: MediaPlayer? = null

    /** 설정대로 소리·진동. preview=true 면 방해금지 무시하고 바로 들려줌 */
    fun ring(c: Context, preview: Boolean = false) {
        val a = alarm(c)
        val mode = a.optString("sound", "sound")
        val ignoreDnd = a.optString("dnd", "respect") == "ignore"
        if (!preview) {
            if (inQuiet(a)) return
            val nm = c.getSystemService(NotificationManager::class.java)
            if (!ignoreDnd && nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL) return
        }
        if (mode == "silent") return
        try {
            val vib = c.getSystemService(Vibrator::class.java)
            vib?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1))
        } catch (e: Exception) { }
        if (mode != "sound") return
        try {
            player?.release()
            val vol = (a.optInt("volume", 80).coerceIn(0, 100)) / 100f
            val p = MediaPlayer()
            p.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(if (ignoreDnd) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            p.setDataSource(c, soundUri(c))
            p.setVolume(vol, vol)
            p.prepare()
            p.setOnCompletionListener { it.release(); if (player === it) player = null }
            p.start()
            player = p
            // 길어도 8초만
            Handler(Looper.getMainLooper()).postDelayed({ try { if (player === p) { p.stop(); p.release(); player = null } } catch (e: Exception) { } }, 8000)
        } catch (e: Exception) { }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val pending = goAsync()
        Notifier.fire(c)
        Notifier.scheduleNext(c)
        // 소리가 끝날 때까지 잠깐 기다림
        Handler(Looper.getMainLooper()).postDelayed({ pending.finish() }, 9000)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Notifier.scheduleNext(c)
    }
}
