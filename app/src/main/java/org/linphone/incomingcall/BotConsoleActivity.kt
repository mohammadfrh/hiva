package org.linphone.incomingcall

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayoutMediator
import org.linphone.incomingcall.bot.ui.BotPagerAdapter
import org.linphone.incomingcall.databinding.ActivityBotConsoleBinding

class BotConsoleActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBotConsoleBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBotConsoleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as IncomingCallApp
        binding.textBackendUrl.text = app.botPrefs.backendBaseUrl

        binding.viewPagerBot.adapter = BotPagerAdapter(this)
        val titles = listOf(
            getString(R.string.bot_tab_dashboard),
            getString(R.string.bot_tab_market),
            getString(R.string.bot_tab_backtest),
            getString(R.string.bot_tab_data),
            getString(R.string.bot_tab_settings)
        )
        TabLayoutMediator(binding.tabsBot, binding.viewPagerBot) { tab, position ->
            tab.text = titles[position]
        }.attach()
    }
}
