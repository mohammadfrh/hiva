package org.linphone.incomingcall.bot.ui

import android.app.DatePickerDialog
import android.icu.util.Calendar as IcuCalendar
import android.icu.util.TimeZone as IcuTimeZone
import android.icu.util.ULocale
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.launch
import org.linphone.incomingcall.R
import org.linphone.incomingcall.bot.local.LocalDataStore
import org.linphone.incomingcall.bot.prettyJson
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

class BotDataFragment : Fragment(R.layout.fragment_bot_data) {
    private val isoDateFormatter: DateTimeFormatter = DateTimeFormatter.ISO_DATE

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val store = LocalDataStore(requireContext())
        val out = view.findViewById<TextView>(R.id.textDataOut)
        val summary = view.findViewById<TextView>(R.id.textDataSummary)
        val greg = view.findViewById<EditText>(R.id.editGregorianDate)
        val sham = view.findViewById<EditText>(R.id.editShamsiDate)
        val start = view.findViewById<EditText>(R.id.editMonthStart)
        val end = view.findViewById<EditText>(R.id.editMonthEnd)
        val shMonth = view.findViewById<EditText>(R.id.editShamsiYearMonth)
        val cacheTf = view.findViewById<EditText>(R.id.editCacheTf)
        val cacheDate = view.findViewById<EditText>(R.id.editCacheDate)
        val cacheMonthStart = view.findViewById<EditText>(R.id.editCacheMonthStart)
        val cacheMonthEnd = view.findViewById<EditText>(R.id.editCacheMonthEnd)
        val cacheMonth = view.findViewById<EditText>(R.id.editCacheMonth)
        setupDatePickerField(greg)
        setupShamsiDatePickerField(sham)
        setupDatePickerField(start)
        setupDatePickerField(end)
        setupShamsiMonthPickerField(shMonth)
        setupDatePickerField(cacheDate)
        setupDatePickerField(cacheMonthStart)
        setupDatePickerField(cacheMonthEnd)
        setupMonthPickerField(cacheMonth)
        val dataAdapter = BotDataRowAdapter { row ->
            if (!row.tf.isNullOrBlank() && !row.date.isNullOrBlank()) {
                cacheTf.setText(row.tf)
                cacheDate.setText(row.date)
                Toast.makeText(requireContext(), "Cache row selected", Toast.LENGTH_SHORT).show()
            }
        }
        view.findViewById<RecyclerView>(R.id.recyclerDataRows).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = dataAdapter
        }

        view.findViewById<Button>(R.id.buttonMockList).setOnClickListener {
            execute(out, summary, dataAdapter) { store.mockList() }
        }
        view.findViewById<Button>(R.id.buttonDownloadDay).setOnClickListener {
            execute(out, summary, dataAdapter) {
                val gRaw = greg.text.toString().trim()
                val sRaw = sham.text.toString().trim()
                val date = when {
                    gRaw.isNotBlank() -> gRaw
                    sRaw.isNotBlank() -> shamsiDateToGregorianIso(sRaw)
                    else -> java.time.LocalDate.now().toString()
                }
                store.mockDownloadDay(date, "1")
            }
        }
        view.findViewById<Button>(R.id.buttonDownloadMonth).setOnClickListener {
            execute(out, summary, dataAdapter) {
                val startRaw = start.text.toString().trim()
                val endRaw = end.text.toString().trim()
                val shMonthRaw = shMonth.text.toString().trim()
                val (s, e) = when {
                    startRaw.isNotBlank() -> {
                        val endVal = endRaw.ifBlank { java.time.LocalDate.parse(startRaw).plusMonths(1).toString() }
                        startRaw to endVal
                    }
                    shMonthRaw.isNotBlank() -> shamsiMonthToGregorianRange(shMonthRaw)
                    else -> {
                        val today = java.time.LocalDate.now()
                        val first = today.withDayOfMonth(1).toString()
                        first to java.time.LocalDate.parse(first).plusMonths(1).toString()
                    }
                }
                store.mockDownloadMonth(s, e, "1")
            }
        }
        view.findViewById<Button>(R.id.buttonCacheList).setOnClickListener {
            execute(out, summary, dataAdapter) { store.cacheList() }
        }
        view.findViewById<Button>(R.id.buttonCacheDelete).setOnClickListener {
            execute(out, summary, dataAdapter) {
                store.cacheDelete(cacheTf.text.toString().trim(), cacheDate.text.toString().trim())
            }
        }
        view.findViewById<Button>(R.id.buttonCacheDownloadDay).setOnClickListener {
            execute(out, summary, dataAdapter) {
                store.cacheDownload(
                    cacheTf.text.toString().trim().ifBlank { "1" },
                    cacheDate.text.toString().trim()
                )
            }
        }
        view.findViewById<Button>(R.id.buttonCacheDownloadMonth).setOnClickListener {
            execute(out, summary, dataAdapter) {
                val monthRaw = cacheMonth.text.toString().trim()
                val tf = cacheTf.text.toString().trim().ifBlank { "1" }
                if (monthRaw.matches(Regex("\\d{4}-\\d{2}"))) {
                    val parts = monthRaw.split("-")
                    store.cacheDownloadMonth(tf, parts[0].toInt(), parts[1].toInt())
                } else {
                    val s = cacheMonthStart.text.toString().trim()
                    val e = cacheMonthEnd.text.toString().trim()
                    val startDate = java.time.LocalDate.parse(s)
                    val endDate = java.time.LocalDate.parse(e)
                    var d = startDate
                    while (!d.isAfter(endDate.minusDays(1))) {
                        store.cacheDownload(tf, d.toString())
                        d = d.plusDays(1)
                    }
                    JsonObject().apply {
                        addProperty("ok", true)
                        addProperty("downloaded", java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate).toInt())
                    }
                }
            }
        }
        summary.text = getString(R.string.bot_data_summary_empty)
    }

    private fun execute(
        out: TextView,
        summary: TextView,
        adapter: BotDataRowAdapter,
        block: suspend () -> JsonElement
    ) {
        out.text = getString(R.string.loading)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val res = block()
                bindDataSummary(summary, res)
                bindDataRows(adapter, res)
                out.text = res.prettyJson()
            } catch (e: Exception) {
                out.text = e.message ?: getString(R.string.network_error)
            }
        }
    }

    private fun bindDataRows(adapter: BotDataRowAdapter, res: JsonElement) {
        val rows = mutableListOf<BotDataRow>()
        if (res.isJsonArray) {
            val arr = res.asJsonArray
            for (i in 0 until arr.size()) {
                val rowObj = arr[i]
                if (!rowObj.isJsonObject) continue
                val o = rowObj.asJsonObject
                rows += when {
                    o.has("filename") -> BotDataRow(
                        title = o.get("filename")?.asString ?: "file",
                        subtitle = "type: mock dataset",
                        meta = "tap for details"
                    )

                    o.has("tf") && o.has("date") -> BotDataRow(
                        title = "cache ${o.get("date")?.asString ?: "-"}",
                        subtitle = "tf: ${o.get("tf")?.asString ?: "-"}",
                        meta = "tap to fill delete form",
                        tf = o.get("tf")?.asString,
                        date = o.get("date")?.asString
                    )

                    else -> BotDataRow(
                        title = "row #${i + 1}",
                        subtitle = "generic entry",
                        meta = "keys: ${o.keySet().size}"
                    )
                }
            }
        } else if (res.isJsonObject) {
            val obj = res.asJsonObject
            val saved = obj.get("saved")?.asString
            val count = obj.get("count")?.asString
            if (!saved.isNullOrBlank() || !count.isNullOrBlank()) {
                rows += BotDataRow(
                    title = "download status",
                    subtitle = "saved: ${saved ?: "-"}",
                    meta = "count: ${count ?: "-"}"
                )
            }
        }
        adapter.submitList(rows)
    }

    private fun bindDataSummary(summary: TextView, res: JsonElement) {
        summary.text = when {
            res.isJsonArray -> {
                val arr = res.asJsonArray
                if (arr.size() == 0) getString(R.string.bot_data_summary_empty)
                else summarizeArray(arr)
            }
            res.isJsonObject -> {
                val obj = res.asJsonObject
                summarizeObject(obj)
            }
            else -> getString(R.string.bot_data_summary_empty)
        }
    }

    private fun summarizeArray(arr: JsonArray): String {
        val first = arr.get(0)
        if (!first.isJsonObject) return "count: ${arr.size()}"
        val o = first.asJsonObject
        return when {
            o.has("filename") -> "files: ${arr.size()} | sample: ${o.get("filename").asString}"
            o.has("tf") && o.has("date") -> "cache_rows: ${arr.size()} | sample_tf: ${o.get("tf").asString}"
            else -> "rows: ${arr.size()}"
        }
    }

    private fun summarizeObject(obj: JsonObject): String {
        return when {
            obj.has("saved") && obj.has("count") ->
                "saved: ${obj.get("saved").asString} | count: ${obj.get("count").asString}"
            obj.has("ok") && obj.has("deleted") ->
                "ok: ${obj.get("ok").asString} | deleted: ${obj.get("deleted").asString}"
            else -> "keys: ${obj.keySet().size}"
        }
    }

    private fun setupDatePickerField(field: EditText) {
        field.inputType = 0
        field.isFocusable = false
        field.isClickable = true
        field.isLongClickable = false
        field.setOnClickListener { showDatePicker(field) }
    }

    private fun setupShamsiDatePickerField(field: EditText) {
        field.inputType = 0
        field.isFocusable = false
        field.isClickable = true
        field.isLongClickable = false
        field.setOnClickListener { showShamsiDatePicker(field) }
    }

    private fun setupShamsiMonthPickerField(field: EditText) {
        field.inputType = 0
        field.isFocusable = false
        field.isClickable = true
        field.isLongClickable = false
        field.setOnClickListener { showShamsiMonthPicker(field) }
    }

    private fun showDatePicker(target: EditText) {
        val seed = parseDateOrToday(target.text?.toString().orEmpty())
        val picker = DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                val selected = LocalDate.of(year, month + 1, dayOfMonth)
                target.setText(selected.format(isoDateFormatter))
            },
            seed.year,
            seed.monthValue - 1,
            seed.dayOfMonth
        )
        picker.show()
    }

    private fun showShamsiDatePicker(target: EditText) {
        val seed = parseShamsiDateOrToday(target.text?.toString().orEmpty())
        val yearPicker = NumberPicker(requireContext()).apply {
            minValue = 1380
            maxValue = 1460
            value = seed.first
            wrapSelectorWheel = false
        }
        val monthPicker = NumberPicker(requireContext()).apply {
            minValue = 1
            maxValue = 12
            value = seed.second
            wrapSelectorWheel = false
        }
        val dayPicker = NumberPicker(requireContext()).apply {
            minValue = 1
            maxValue = shamsiDaysInMonth(seed.first, seed.second)
            value = seed.third.coerceAtMost(maxValue)
            wrapSelectorWheel = false
        }
        fun syncDayMax() {
            val max = shamsiDaysInMonth(yearPicker.value, monthPicker.value)
            dayPicker.maxValue = max
            if (dayPicker.value > max) dayPicker.value = max
        }
        yearPicker.setOnValueChangedListener { _, _, _ -> syncDayMax() }
        monthPicker.setOnValueChangedListener { _, _, _ -> syncDayMax() }

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 24, 24, 8)
            addView(yearPicker, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(monthPicker, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(dayPicker, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Select Shamsi Date")
            .setView(root)
            .setPositiveButton("OK") { _, _ ->
                target.setText(
                    String.format(
                        Locale.US,
                        "%04d-%02d-%02d",
                        yearPicker.value,
                        monthPicker.value,
                        dayPicker.value
                    )
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showShamsiMonthPicker(target: EditText) {
        val seed = parseShamsiYearMonthOrToday(target.text?.toString().orEmpty())
        val yearPicker = NumberPicker(requireContext()).apply {
            minValue = 1380
            maxValue = 1460
            value = seed.first
            wrapSelectorWheel = false
        }
        val monthPicker = NumberPicker(requireContext()).apply {
            minValue = 1
            maxValue = 12
            value = seed.second
            wrapSelectorWheel = false
        }
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 24, 24, 8)
            addView(yearPicker, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(monthPicker, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Select Shamsi Month")
            .setView(root)
            .setPositiveButton("OK") { _, _ ->
                target.setText(String.format(Locale.US, "%04d-%02d", yearPicker.value, monthPicker.value))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupMonthPickerField(field: EditText) {
        field.inputType = 0
        field.isFocusable = false
        field.isClickable = true
        field.isLongClickable = false
        field.setOnClickListener { showMonthPicker(field) }
    }

    private fun showMonthPicker(target: EditText) {
        val seed = parseYearMonthOrToday(target.text?.toString().orEmpty())
        val picker = DatePickerDialog(
            requireContext(),
            { _, year, month, _ ->
                target.setText(String.format(Locale.US, "%04d-%02d", year, month + 1))
            },
            seed.first,
            seed.second - 1,
            1
        )
        val dayId = resources.getIdentifier("day", "id", "android")
        if (dayId != 0) {
            picker.datePicker.findViewById<View>(dayId)?.visibility = View.GONE
        }
        picker.show()
    }

    private fun parseDateOrToday(raw: String): LocalDate {
        return try {
            LocalDate.parse(raw.trim(), isoDateFormatter)
        } catch (_: Exception) {
            val now = Calendar.getInstance()
            LocalDate.of(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH))
        }
    }

    private fun parseYearMonthOrToday(raw: String): Pair<Int, Int> {
        val trimmed = raw.trim()
        if (trimmed.matches(Regex("\\d{4}-\\d{2}"))) {
            val parts = trimmed.split("-")
            return parts[0].toInt() to parts[1].toInt()
        }
        val now = Calendar.getInstance()
        return now.get(Calendar.YEAR) to (now.get(Calendar.MONTH) + 1)
    }

    private fun shamsiDateToGregorianIso(raw: String): String {
        val (y, m, d) = parseShamsiDateOrToday(raw)
        val cal = IcuCalendar.getInstance(
            IcuTimeZone.getTimeZone("Asia/Tehran"),
            ULocale.forLanguageTag("fa-IR-u-ca-persian")
        )
        cal.clear()
        cal.set(IcuCalendar.YEAR, y)
        cal.set(IcuCalendar.MONTH, m - 1)
        cal.set(IcuCalendar.DAY_OF_MONTH, d)
        cal.set(IcuCalendar.HOUR_OF_DAY, 0)
        cal.set(IcuCalendar.MINUTE, 0)
        cal.set(IcuCalendar.SECOND, 0)
        val date = java.time.Instant.ofEpochMilli(cal.timeInMillis)
            .atZone(ZoneId.of("Asia/Tehran"))
            .toLocalDate()
        return date.format(isoDateFormatter)
    }

    private fun shamsiMonthToGregorianRange(raw: String): Pair<String, String> {
        val (y, m) = parseShamsiYearMonthOrToday(raw)
        val start = shamsiDateToGregorianIso(String.format(Locale.US, "%04d-%02d-01", y, m))
        val nextYm = if (m == 12) (y + 1 to 1) else (y to (m + 1))
        val endExclusive = shamsiDateToGregorianIso(
            String.format(Locale.US, "%04d-%02d-01", nextYm.first, nextYm.second)
        )
        return start to endExclusive
    }

    private fun parseShamsiDateOrToday(raw: String): Triple<Int, Int, Int> {
        val normalized = normalizeDigits(raw.trim())
        if (normalized.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
            val parts = normalized.split("-")
            val y = parts[0].toInt()
            val m = parts[1].toInt()
            val d = parts[2].toInt()
            return Triple(y, m, d)
        }
        val now = IcuCalendar.getInstance(
            IcuTimeZone.getTimeZone("Asia/Tehran"),
            ULocale.forLanguageTag("fa-IR-u-ca-persian")
        )
        return Triple(
            now.get(IcuCalendar.YEAR),
            now.get(IcuCalendar.MONTH) + 1,
            now.get(IcuCalendar.DAY_OF_MONTH)
        )
    }

    private fun parseShamsiYearMonthOrToday(raw: String): Pair<Int, Int> {
        val normalized = normalizeDigits(raw.trim())
        if (normalized.matches(Regex("\\d{4}-\\d{2}"))) {
            val parts = normalized.split("-")
            return parts[0].toInt() to parts[1].toInt()
        }
        val now = IcuCalendar.getInstance(
            IcuTimeZone.getTimeZone("Asia/Tehran"),
            ULocale.forLanguageTag("fa-IR-u-ca-persian")
        )
        return now.get(IcuCalendar.YEAR) to (now.get(IcuCalendar.MONTH) + 1)
    }

    private fun shamsiDaysInMonth(year: Int, month: Int): Int {
        return when {
            month in 1..6 -> 31
            month in 7..11 -> 30
            month == 12 -> if (isShamsiLeap(year)) 30 else 29
            else -> 30
        }
    }

    private fun isShamsiLeap(year: Int): Boolean {
        val mod = year % 33
        return mod in listOf(1, 5, 9, 13, 17, 22, 26, 30)
    }

    private fun normalizeDigits(value: String): String = buildString(value.length) {
        value.forEach { c ->
            append(
                when (c) {
                    '۰' -> '0'
                    '۱' -> '1'
                    '۲' -> '2'
                    '۳' -> '3'
                    '۴' -> '4'
                    '۵' -> '5'
                    '۶' -> '6'
                    '۷' -> '7'
                    '۸' -> '8'
                    '۹' -> '9'
                    else -> c
                }
            )
        }
    }
}
