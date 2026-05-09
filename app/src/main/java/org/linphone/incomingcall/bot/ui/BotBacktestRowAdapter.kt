package org.linphone.incomingcall.bot.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.linphone.incomingcall.databinding.ItemBotBacktestRowBinding

data class BotBacktestRow(
    val dataset: String,
    val profile: String,
    val subtitle: String
)

private val BACKTEST_DIFF = object : DiffUtil.ItemCallback<BotBacktestRow>() {
    override fun areItemsTheSame(oldItem: BotBacktestRow, newItem: BotBacktestRow): Boolean = oldItem == newItem
    override fun areContentsTheSame(oldItem: BotBacktestRow, newItem: BotBacktestRow): Boolean = oldItem == newItem
}

class BotBacktestRowAdapter(
    private val onClick: (BotBacktestRow) -> Unit
) : ListAdapter<BotBacktestRow, BotBacktestRowAdapter.VH>(BACKTEST_DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemBotBacktestRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding, onClick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(
        private val binding: ItemBotBacktestRowBinding,
        private val onClick: (BotBacktestRow) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: BotBacktestRow) {
            binding.textBacktestRowTitle.text = "${item.dataset} | ${item.profile}"
            binding.textBacktestRowSubtitle.text = item.subtitle
            binding.root.setOnClickListener { onClick(item) }
        }
    }
}
