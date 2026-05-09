package org.linphone.incomingcall.bot.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.linphone.incomingcall.databinding.ItemBotSignalBinding

data class BotSignalRow(
    val type: String,
    val direction: String,
    val pnl: String
)

private val DIFF = object : DiffUtil.ItemCallback<BotSignalRow>() {
    override fun areItemsTheSame(oldItem: BotSignalRow, newItem: BotSignalRow): Boolean {
        return oldItem == newItem
    }

    override fun areContentsTheSame(oldItem: BotSignalRow, newItem: BotSignalRow): Boolean {
        return oldItem == newItem
    }
}

class BotSignalAdapter : ListAdapter<BotSignalRow, BotSignalAdapter.VH>(DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemBotSignalBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(private val binding: ItemBotSignalBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: BotSignalRow) {
            binding.textType.text = item.type
            binding.textDirection.text = item.direction
            binding.textPnl.text = item.pnl
        }
    }
}
