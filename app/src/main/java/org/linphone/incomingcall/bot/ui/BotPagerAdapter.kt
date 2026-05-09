package org.linphone.incomingcall.bot.ui

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

class BotPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 5

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> BotDashboardFragment()
            1 -> BotMarketFragment()
            2 -> BotBacktestFragment()
            3 -> BotDataFragment()
            else -> BotSettingsFragment()
        }
    }
}
