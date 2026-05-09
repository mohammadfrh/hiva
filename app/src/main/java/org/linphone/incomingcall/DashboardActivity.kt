package org.linphone.incomingcall

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.linphone.incomingcall.databinding.ActivityDashboardBinding
import org.linphone.incomingcall.hiva.HivaGoldClient
import org.linphone.incomingcall.hiva.TransactionItem
import org.linphone.incomingcall.hiva.PortfolioItem
import org.linphone.incomingcall.hiva.formatTomanPersian
import org.linphone.incomingcall.hiva.toPersianDigits
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import retrofit2.HttpException

class DashboardActivity : AppCompatActivity() {
    private val wsTestTag = "BT_WS_TEST"

    private lateinit var binding: ActivityDashboardBinding
    private val txAdapter = TransactionAdapter()
    private val pfAdapter = PortfolioAdapter()

    private var selectedTab = 0
    private var txPage = 1
    private var pfPage = 1
    private var wsTestSocket: WebSocket? = null
    private var wsTestGuardJob: Job? = null
    private var wsLastAttemptAtMs: Long = 0L
    private var wsFrameCount: Int = 0
    @Volatile private var wsStopExpected: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as IncomingCallApp
        if (!app.sessionStore.isLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tabsHistory.addTab(binding.tabsHistory.newTab().setText(R.string.tab_transactions))
        binding.tabsHistory.addTab(binding.tabsHistory.newTab().setText(R.string.tab_portfolios))

        binding.recyclerHistory.layoutManager = LinearLayoutManager(this)
        binding.recyclerHistory.adapter = txAdapter

        binding.buttonNext.setOnClickListener { bumpPage(1) }
        binding.buttonPrev.setOnClickListener { bumpPage(-1) }
        binding.buttonOpenBotConsole.setOnClickListener {
            startActivity(Intent(this, BotConsoleActivity::class.java))
        }
        binding.buttonOpenAutoTrade.setOnClickListener {
            startActivity(Intent(this, AutoTradeActivity::class.java))
        }
        binding.buttonTestWsDashboard.setOnClickListener {
            startWsSafeTest()
        }

        binding.tabsHistory.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                selectedTab = tab?.position ?: 0
                updateListForTab()
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        loadProfile()
    }

    override fun onDestroy() {
        stopWsSafeTest()
        super.onDestroy()
    }

    private fun bumpPage(delta: Int) {
        if (selectedTab == 0) {
            val next = txPage + delta
            if (next < 1) return
            txPage = next
        } else {
            val next = pfPage + delta
            if (next < 1) return
            pfPage = next
        }
        loadProfile()
    }

    private fun updateListForTab() {
        binding.recyclerHistory.adapter = if (selectedTab == 0) txAdapter else pfAdapter
        loadProfile()
    }

    private fun loadProfile() {
        binding.progressDashboard.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                // 1. Fetch User Info & Balance
                val userInfo = HivaGoldClient.api.getUserInfo()
                binding.textPhone.text = userInfo.username.toPersianDigits()
                binding.textBalance.text = getString(
                    R.string.balance_line,
                    userInfo.balance.formatTomanPersian()
                )

                // 2. Fetch Transactions
                val txJson = HivaGoldClient.api.getMazanehTransactions(txPage)
                val transactions = mutableListOf<TransactionItem>()
                listOf("open", "closed").forEach { key ->
                    txJson.getAsJsonArray(key)?.forEach { elem ->
                        val obj = elem.asJsonObject
                        transactions.add(
                            TransactionItem(
                                id = obj.get("id").asLong,
                                units = obj.get("units")?.asDouble ?: 0.0,
                                entryPrice = obj.get("entry_price")?.asDouble ?: 0.0,
                                closePrice = obj.get("close_price")?.asDouble ?: 0.0,
                                takeProfit = obj.get("take_profit")?.asDouble ?: 0.0,
                                stopLoss = obj.get("stop_loss")?.asDouble ?: 0.0,
                                action = obj.get("action")?.asString ?: "buy",
                                pnl = obj.get("pnl")?.asDouble ?: 0.0,
                                fee = obj.get("fee")?.asDouble ?: 0.0,
                                status = obj.get("status")?.asString ?: "unknown",
                                statusDisplay = obj.get("status_display")?.asString
                            )
                        )
                    }
                }
                txAdapter.submitList(transactions)

                // 3. Fetch Active Portfolio
                try {
                    val portfolio = HivaGoldClient.api.getMazanehActivePortfolio()
                    val pfItem = PortfolioItem(
                        id = portfolio.id,
                        type = portfolio.portfolioType,
                        metric = 0.0,
                        amountIn = portfolio.initialBalance,
                        amountOut = portfolio.totalBalance,
                        profitLoss = portfolio.totalBalance - portfolio.initialBalance,
                        status = portfolio.status,
                        createdAt = "",
                        endTime = null
                    )
                    pfAdapter.submitList(listOf(pfItem))
                } catch (e: Exception) {
                    pfAdapter.submitList(emptyList())
                }

                // 4. Update Pagination UI
                val count = txJson.get("count")?.asInt ?: 0
                val pages = (count + 19) / 20 // Assume 20 per page
                binding.textPageInfo.text = getString(
                    R.string.page_of,
                    txPage.toString().toPersianDigits(),
                    pages.toString().toPersianDigits()
                )
                binding.buttonPrev.isEnabled = txPage > 1
                binding.buttonNext.isEnabled = txPage < pages

                // Live Stats (Optional placeholders for now)
                binding.textOpenTrades.text = "—".toPersianDigits()
                binding.textLivePnl.text = 0L.formatTomanPersian()

            } catch (e: HttpException) {
                if (e.code() == 401) {
                    val app = application as IncomingCallApp
                    app.sessionStore.clear()
                    startActivity(Intent(this@DashboardActivity, LoginActivity::class.java))
                    finish()
                } else {
                    Toast.makeText(
                        this@DashboardActivity,
                        "Error ${e.code()}: ${e.message()}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@DashboardActivity,
                    e.message ?: getString(R.string.network_error),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                binding.progressDashboard.visibility = View.GONE
            }
        }
    }

    private fun startWsSafeTest() {
        val now = System.currentTimeMillis()
        if (now - wsLastAttemptAtMs < 30_000L) {
            binding.textWsTestStatus.text = "WS test cooldown: wait a few seconds"
            return
        }
        wsLastAttemptAtMs = now
        wsFrameCount = 0
        wsStopExpected = false
        stopWsSafeTest()

        val token = HivaGoldClient.accessTokenOrNull()
        if (token.isNullOrBlank()) {
            binding.textWsTestStatus.text = "WS test failed: missing access token"
            return
        }

        val url = "wss://demo.hivagold.org/ws/?token=$token"
        binding.textWsTestStatus.text = "WS test: connecting..."
        Log.i(wsTestTag, "ws test connect url=$url")
        val request = Request.Builder().url(url).build()
        val openedAt = System.currentTimeMillis()

        wsTestSocket = HivaGoldClient.okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(wsTestTag, "ws open code=${response.code} message=${response.message}")
                runOnUiThread { binding.textWsTestStatus.text = "WS test: opened, subscribing..." }
                webSocket.send("""{"action":"subscribe_all"}""")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                wsFrameCount += 1
                if (text.contains("\"type\":\"ping\"") || text.contains("\"type\": \"ping\"")) {
                    Log.i(wsTestTag, "ws ping -> pong")
                    webSocket.send("""{"type":"pong"}""")
                    if (wsFrameCount <= 30) {
                        Log.d(wsTestTag, "ws frame[$wsFrameCount] ping")
                    }
                    return
                }
                if (text.contains("Transferring to the website", ignoreCase = true) ||
                    text.contains("__arcsjs", ignoreCase = true)
                ) {
                    Log.w(wsTestTag, "ws blocked by waf challenge frameCount=$wsFrameCount")
                    runOnUiThread { binding.textWsTestStatus.text = "WS test blocked by WAF challenge, stopped" }
                    stopWsSafeTest()
                    return
                }

                val price = extractJsonNumberField(text, "price")
                val bestBid = extractFirstNestedPrice(text, "data_buy")
                val bestAsk = extractFirstNestedPrice(text, "data_sell")
                if (price != null) {
                    val elapsed = System.currentTimeMillis() - openedAt
                    Log.i(
                        wsTestTag,
                        "ws tick frame=$wsFrameCount elapsedMs=$elapsed price=$price bid=${bestBid ?: "-"} ask=${bestAsk ?: "-"}"
                    )
                    runOnUiThread {
                        binding.textWsTestStatus.text =
                            "WS live: price=${price.toLong()} bid=${bestBid?.toLong() ?: "-"} ask=${bestAsk?.toLong() ?: "-"} frames=$wsFrameCount"
                    }
                } else if (wsFrameCount <= 30) {
                    Log.d(wsTestTag, "ws frame[$wsFrameCount] ${text.take(240)}")
                }

                if (wsFrameCount >= 80) {
                    Log.i(wsTestTag, "ws test max frames reached -> stop")
                    runOnUiThread { binding.textWsTestStatus.text = "WS test done: max frames reached, stopped" }
                    stopWsSafeTest()
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(wsTestTag, "ws closing code=$code reason=$reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(wsTestTag, "ws closed code=$code reason=$reason frames=$wsFrameCount")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (wsStopExpected && (t.message?.contains("Socket closed", ignoreCase = true) == true)) {
                    Log.i(wsTestTag, "ws closed by local stop (expected)")
                    return
                }
                Log.e(wsTestTag, "ws failure code=${response?.code} message=${t.message}", t)
                runOnUiThread {
                    binding.textWsTestStatus.text = "WS test failed: ${t.message ?: "unknown"}"
                }
                stopWsSafeTest()
            }
        })

        wsTestGuardJob = lifecycleScope.launch {
            delay(45_000L)
            if (wsTestSocket != null) {
                Log.i(wsTestTag, "ws test timeout guard -> stop frames=$wsFrameCount")
                binding.textWsTestStatus.text = "WS test timeout, stopped for safety"
                stopWsSafeTest()
            }
        }
    }

    private fun stopWsSafeTest() {
        wsStopExpected = true
        wsTestGuardJob?.cancel()
        wsTestGuardJob = null
        wsTestSocket?.cancel()
        wsTestSocket = null
    }

    private fun extractJsonNumberField(payload: String, field: String): Double? {
        val key = "\"$field\":"
        val start = payload.indexOf(key)
        if (start < 0) return null
        val from = start + key.length
        var end = from
        while (end < payload.length && (payload[end].isDigit() || payload[end] == '.')) {
            end += 1
        }
        if (end <= from) return null
        return payload.substring(from, end).toDoubleOrNull()
    }

    private fun extractFirstNestedPrice(payload: String, arrayField: String): Double? {
        val key = "\"$arrayField\":["
        val idx = payload.indexOf(key)
        if (idx < 0) return null
        val slice = payload.substring(idx)
        val priceKey = "\"price\":"
        val p = slice.indexOf(priceKey)
        if (p < 0) return null
        val from = p + priceKey.length
        var end = from
        while (end < slice.length && (slice[end].isDigit() || slice[end] == '.')) {
            end += 1
        }
        if (end <= from) return null
        return slice.substring(from, end).toDoubleOrNull()
    }
}
