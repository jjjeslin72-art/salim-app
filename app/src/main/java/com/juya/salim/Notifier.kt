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
import org.json.JSONArray
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
    private const val CH = "daily"
    private const val NOTI_ID = 1

    fun pt(c: Context) = prefs(c).getString("lang", "ko") == "pt"

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("salim", Context.MODE_PRIVATE)

    fun ensureChannel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) {
            val ch = NotificationChannel(CH, "살림 알림 / Avisos", NotificationManager.IMPORTANCE_DEFAULT)
            ch.description = "다시 살 물건, 먼저 먹을 재료 알림"
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
            .setContentTitle(if (force) (if (pt) "Aviso (teste)" else "살림 알림 (시험)")
                else (if (pt) "Hoje: ${lines.size} item(ns) para ver" else "오늘 챙길 것 ${lines.size}개"))
            .setContentText(lines.first())
            .setStyle(style)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        return try {
            c.getSystemService(NotificationManager::class.java).notify(NOTI_ID, n); true
        } catch (e: SecurityException) { false }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Notifier.fire(c)
        Notifier.scheduleNext(c)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Notifier.scheduleNext(c)
    }
}
