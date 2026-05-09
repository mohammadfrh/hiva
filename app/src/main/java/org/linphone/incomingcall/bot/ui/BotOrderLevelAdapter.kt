package org.linphone.incomingcall.bot.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.linphone.incomingcall.databinding.ItemBotOrderLevelBinding

data class BotOrderLevelRow(
    val price: String,
    val qty: String
)

private val ORDER_LEVEL_DIFF = object : DiffUtil.ItemCallback<BotOrderLevelRow>() {
    override fun areItemsTheSame(oldItem: BotOrderLevelRow, newItem: BotOrderLevelRow): Boolean = oldItem == newItem
    override fun areContentsTheSame(oldItem: BotOrderLevelRow, newItem: BotOrderLevelRow): Boolean = oldItem == newItem
}

class BotOrderLevelAdapter : ListAdapter<BotOrderLevelRow, BotOrderLevelAdapter.VH>(ORDER_LEVEL_DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemBotOrderLevelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(private val binding: ItemBotOrderLevelBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: BotOrderLevelRow) {
            binding.textPrice.text = item.price
            binding.textQty.text = item.qty
        }
    }
}
