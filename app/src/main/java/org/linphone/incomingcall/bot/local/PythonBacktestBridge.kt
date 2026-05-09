package org.linphone.incomingcall.bot.local

import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.linphone.incomingcall.IncomingCallApp

object PythonBacktestBridge {
    private const val TAG = "PY_BT_BRIDGE"
    private val gson = Gson()

    private fun ensureStarted() {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(IncomingCallApp.instance.applicationContext))
            Log.i(TAG, "Python runtime started")
        }
    }

    fun runBacktest(
        profileId: String,
        candles1m: List<LocalCandle>,
        mtf: Map<Int, List<LocalCandle>>
    ): JsonObject {
        ensureStarted()
        val py = Python.getInstance()
        val module = py.getModule("py_exact_backtest_runner")
        val candlesJson = gson.toJson(candles1m)
        val mtfJson = gson.toJson(mtf.mapKeys { "${it.key}m" })
        val result = module.callAttr("run_payload_backtest_json", profileId, candlesJson, mtfJson, "live").toString()
        return JsonParser.parseString(result).asJsonObject
    }

    fun runSignal(
        profileId: String,
        candles1m: List<LocalCandle>,
        mtf: Map<Int, List<LocalCandle>>
    ): JsonObject {
        ensureStarted()
        val py = Python.getInstance()
        val module = py.getModule("py_exact_backtest_runner")
        val candlesJson = gson.toJson(candles1m)
        val mtfJson = gson.toJson(mtf.mapKeys { "${it.key}m" })
        val result = module.callAttr("run_payload_signal_json", profileId, candlesJson, mtfJson).toString()
        return JsonParser.parseString(result).asJsonObject
    }

    fun runBacktestFromDataset(
        profileId: String,
        datasetId: String,
        mockDirPath: String,
        outputsDirPath: String
    ): JsonObject {
        ensureStarted()
        val py = Python.getInstance()
        val module = py.getModule("py_exact_backtest_runner")
        val result = module.callAttr(
            "run_dataset_backtest_json",
            profileId,
            datasetId,
            mockDirPath,
            outputsDirPath
        ).toString()
        return JsonParser.parseString(result).asJsonObject
    }
}

