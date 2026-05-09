package org.linphone.incomingcall.bot.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.linphone.incomingcall.databinding.ItemBotPositionOrderBinding

data class BotPositionOrderRow(
    val title: String,
    val subtitle: String
)

private val POS_ORDER_DIFF = object : DiffUtil.ItemCallback<BotPositionOrderRow>() {
    override fun areItemsTheSame(oldItem: BotPositionOrderRow, newItem: BotPositionOrderRow): Boolean = oldItem == newItem
    override fun areContentsTheSame(oldItem: BotPositionOrderRow, newItem: BotPositionOrderRow): Boolean = oldItem == newItem
}

class BotPositionOrderAdapter : ListAdapter<BotPositionOrderRow, BotPositionOrderAdapter.VH>(POS_ORDER_DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemBotPositionOrderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(private val binding: ItemBotPositionOrderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: BotPositionOrderRow) {
            binding.textTitle.text = item.title
            binding.textSubtitle.text = item.subtitle
        }
    }
}
