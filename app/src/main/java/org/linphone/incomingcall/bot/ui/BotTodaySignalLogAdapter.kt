package org.linphone.incomingcall.bot.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.linphone.incomingcall.databinding.ItemBotTodaySignalLogBinding

data class BotTodaySignalLogRow(
    val title: String,
    val subtitle: String
)

private val SIGNAL_LOG_DIFF = object : DiffUtil.ItemCallback<BotTodaySignalLogRow>() {
    override fun areItemsTheSame(oldItem: BotTodaySignalLogRow, newItem: BotTodaySignalLogRow): Boolean = oldItem == newItem
    override fun areContentsTheSame(oldItem: BotTodaySignalLogRow, newItem: BotTodaySignalLogRow): Boolean = oldItem == newItem
}

class BotTodaySignalLogAdapter : ListAdapter<BotTodaySignalLogRow, BotTodaySignalLogAdapter.VH>(SIGNAL_LOG_DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemBotTodaySignalLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(private val binding: ItemBotTodaySignalLogBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: BotTodaySignalLogRow) {
            binding.textSignalLogTitle.text = item.title
            binding.textSignalLogSubtitle.text = item.subtitle
        }
    }
}
