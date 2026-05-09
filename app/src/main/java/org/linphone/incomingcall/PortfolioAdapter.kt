package org.linphone.incomingcall

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.linphone.incomingcall.databinding.ItemPortfolioBinding
import org.linphone.incomingcall.hiva.PortfolioItem
import org.linphone.incomingcall.hiva.formatTomanPersian
import org.linphone.incomingcall.hiva.portfolioTypeFa
import org.linphone.incomingcall.hiva.tradeStatusFa

private val PORTFOLIO_DIFF = object : DiffUtil.ItemCallback<PortfolioItem>() {
    override fun areItemsTheSame(a: PortfolioItem, b: PortfolioItem) = a.id == b.id
    override fun areContentsTheSame(a: PortfolioItem, b: PortfolioItem) = a == b
}

class PortfolioAdapter : ListAdapter<PortfolioItem, PortfolioAdapter.VH>(PORTFOLIO_DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemPortfolioBinding.inflate(inflater, parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(private val binding: ItemPortfolioBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: PortfolioItem) {
            val ctx = binding.root.context
            binding.textTitle.text = portfolioTypeFa(item.type)
            binding.textAmountIn.text = ctx.getString(
                R.string.portfolio_amount_in,
                item.amountIn.toLong().formatTomanPersian()
            )
            binding.textAmountOut.text = ctx.getString(
                R.string.portfolio_amount_out,
                item.amountOut.toLong().formatTomanPersian()
            )
            val pnlColor = if (item.profitLoss >= 0) Color.parseColor("#4CAF50") else Color.parseColor("#EF5350")
            binding.textPnl.setTextColor(pnlColor)
            binding.textPnl.text = ctx.getString(
                R.string.label_pnl_value,
                item.profitLoss.toLong().formatTomanPersian()
            )
            binding.textStatus.text = ctx.getString(
                R.string.portfolio_status,
                tradeStatusFa(item.status)
            )
        }
    }
}
