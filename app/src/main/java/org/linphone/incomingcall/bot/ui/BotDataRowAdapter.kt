package org.linphone.incomingcall.bot.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.linphone.incomingcall.databinding.ItemBotDataRowBinding

data class BotDataRow(
    val title: String,
    val subtitle: String,
    val meta: String,
    val tf: String? = null,
    val date: String? = null
)

private val DATA_DIFF = object : DiffUtil.ItemCallback<BotDataRow>() {
    override fun areItemsTheSame(oldItem: BotDataRow, newItem: BotDataRow): Boolean = oldItem == newItem
    override fun areContentsTheSame(oldItem: BotDataRow, newItem: BotDataRow): Boolean = oldItem == newItem
}

class BotDataRowAdapter(
    private val onClick: (BotDataRow) -> Unit
) : ListAdapter<BotDataRow, BotDataRowAdapter.VH>(DATA_DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemBotDataRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding, onClick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(
        private val binding: ItemBotDataRowBinding,
        private val onClick: (BotDataRow) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: BotDataRow) {
            binding.textDataTitle.text = item.title
            binding.textDataSubtitle.text = item.subtitle
            binding.textDataMeta.text = item.meta
            binding.root.setOnClickListener { onClick(item) }
        }
    }
}
