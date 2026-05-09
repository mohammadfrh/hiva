package org.linphone.incomingcall

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.linphone.incomingcall.databinding.ItemTransactionBinding
import org.linphone.incomingcall.hiva.TransactionItem
import org.linphone.incomingcall.hiva.formatIsoToJalaliPersian
import org.linphone.incomingcall.hiva.formatNumberPersian
import org.linphone.incomingcall.hiva.formatTomanPersian
import org.linphone.incomingcall.hiva.toPersianDigits
import org.linphone.incomingcall.hiva.transactionTitleFa

private val TRANSACTION_DIFF = object : DiffUtil.ItemCallback<TransactionItem>() {
    override fun areItemsTheSame(a: TransactionItem, b: TransactionItem) = a.id == b.id
    override fun areContentsTheSame(a: TransactionItem, b: TransactionItem) = a == b
}

class TransactionAdapter : ListAdapter<TransactionItem, TransactionAdapter.VH>(TRANSACTION_DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemTransactionBinding.inflate(inflater, parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(private val binding: ItemTransactionBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: TransactionItem) {
            val ctx = binding.root.context
            val unitsStr = item.units.toString().removeSuffix(".0")
            val title = transactionTitleFa(item.action, unitsStr).toPersianDigits()
            binding.textTitle.text = title
            val titleColor = when (item.action) {
                "buy" -> Color.parseColor("#4CAF50")
                "sell" -> Color.parseColor("#EF5350")
                else -> Color.parseColor("#FFFFFF")
            }
            binding.textTitle.setTextColor(titleColor)

            binding.textAmountIn.text = ctx.getString(
                R.string.label_amount_in_value,
                item.entryPrice.toLong().formatNumberPersian()
            )
            binding.textMetric.text = ctx.getString(
                R.string.label_metric_value,
                item.takeProfit.toLong().formatNumberPersian() // Show TP as metric or keep old logic?
            )
            binding.textAmountOut.text = ctx.getString(
                R.string.label_amount_out_value,
                item.closePrice.toLong().formatNumberPersian()
            )
            val pnlColor = if (item.pnl >= 0) Color.parseColor("#4CAF50") else Color.parseColor("#EF5350")
            binding.textPnl.setTextColor(pnlColor)
            binding.textPnl.text = ctx.getString(
                R.string.label_pnl_value,
                item.pnl.toLong().formatTomanPersian()
            )
            binding.textOpenedAt.text = ctx.getString(
                R.string.label_opened_at,
                "—" // Hiva transaction list response didn't have opened_at in the snippet
            )
            binding.textClosedAt.text = ctx.getString(R.string.label_closed_at, item.statusDisplay ?: "—")
        }
    }
}
