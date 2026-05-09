package org.linphone.incomingcall.bot.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.linphone.incomingcall.databinding.ItemBotTradeRowBinding

data class BotTradeRow(
    val index: Int,
    val direction: String,
    val entryTime: String,
    val exitTime: String,
    val entryPrice: String,
    val exitPrice: String,
    val pnl: String,
    val pnlColor: Int,
    val reason: String,
    val score: String
)

private val TRADE_DIFF = object : DiffUtil.ItemCallback<BotTradeRow>() {
    override fun areItemsTheSame(oldItem: BotTradeRow, newItem: BotTradeRow): Boolean =
        oldItem.index == newItem.index && oldItem.entryTime == newItem.entryTime

    override fun areContentsTheSame(oldItem: BotTradeRow, newItem: BotTradeRow): Boolean = oldItem == newItem
}

class BotTradeRowAdapter : ListAdapter<BotTradeRow, BotTradeRowAdapter.VH>(TRADE_DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemBotTradeRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(
        private val binding: ItemBotTradeRowBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: BotTradeRow) {
            binding.textTradeTitle.text =
                "#${item.index} ${item.direction.uppercase()} | ${item.entryTime} -> ${item.exitTime}"
            binding.textTradeMeta.text =
                "Entry ${item.entryPrice} | Exit ${item.exitPrice} | ${item.reason} | score ${item.score}"
            binding.textTradePnl.text = item.pnl
            binding.textTradePnl.setTextColor(item.pnlColor)
            if (item.pnlColor == Color.parseColor("#4CAF50")) {
                binding.textTradePnl.setBackgroundColor(Color.parseColor("#1A4CAF50"))
            } else {
                binding.textTradePnl.setBackgroundColor(Color.parseColor("#1AEF5350"))
            }
        }
    }
}

