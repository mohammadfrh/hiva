package org.linphone.incomingcall.hiva

import android.icu.util.Calendar as IcuCalendar
import android.icu.util.ULocale
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

private val TEHRAN: ZoneId = ZoneId.of("Asia/Tehran")

fun Long.formatTomanPersian(): String =
    String.format(Locale.US, "%,d", this).replace(',', '٬').toPersianDigits() + " تومان"

fun Long.formatNumberPersian(): String =
    String.format(Locale.US, "%,d", this).replace(',', '٬').toPersianDigits()

fun String.toPersianDigits(): String = buildString(length) {
    for (c in this@toPersianDigits) {
        append(
            when (c) {
                '0' -> '۰'
                '1' -> '۱'
                '2' -> '۲'
                '3' -> '۳'
                '4' -> '۴'
                '5' -> '۵'
                '6' -> '۶'
                '7' -> '۷'
                '8' -> '۸'
                '9' -> '۹'
                else -> c
            }
        )
    }
}

fun formatIsoToJalaliPersian(iso: String): String {
    return try {
        val instant = Instant.parse(iso)
        val millis = instant.atZone(TEHRAN).toInstant().toEpochMilli()
        val cal = IcuCalendar.getInstance(ULocale.forLanguageTag("fa-IR-u-ca-persian"))
        cal.timeInMillis = millis
        val y = cal.get(IcuCalendar.YEAR)
        val m = cal.get(IcuCalendar.MONTH) + 1
        val d = cal.get(IcuCalendar.DAY_OF_MONTH)
        val hour = cal.get(IcuCalendar.HOUR_OF_DAY)
        val minute = cal.get(IcuCalendar.MINUTE)
        String.format("%d/%02d/%02d %02d:%02d", y, m, d, hour, minute).toPersianDigits()
    } catch (_: Exception) {
        iso.toPersianDigits()
    }
}

fun portfolioTypeFa(type: String): String = when (type) {
    "isolated" -> "معمولی"
    else -> type
}

fun transactionTitleFa(type: String, units: String): String {
    val verb = when (type) {
        "sell" -> "فروش"
        "buy" -> "خرید"
        else -> type
    }
    val u = units.removeSuffix(".0").removeSuffix(".00")
    return "$verb - $u واحد"
}

fun tradeStatusFa(status: String): String = when (status) {
    "closed" -> "بسته"
    "open" -> "باز"
    else -> status
}
