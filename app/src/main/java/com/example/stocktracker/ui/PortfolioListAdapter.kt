package com.example.stocktracker.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.stocktracker.R
import com.example.stocktracker.data.PortfolioSummary
import com.example.stocktracker.databinding.ListItemPortfolioBinding
import com.example.stocktracker.ui.components.formatCurrency

class PortfolioListAdapter(
    private val onPortfolioClick: (PortfolioSummary) -> Unit
) : ListAdapter<PortfolioSummary, PortfolioListAdapter.PortfolioViewHolder>(PortfolioDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PortfolioViewHolder {
        val binding = ListItemPortfolioBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PortfolioViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PortfolioViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PortfolioViewHolder(private val binding: ListItemPortfolioBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onPortfolioClick(getItem(position))
                }
            }
        }

        fun bind(summary: PortfolioSummary) {
            binding.textViewPortfolioName.text = summary.portfolio.name
            binding.textViewTotalValue.text = formatCurrency(summary.totalMarketValue, false)

            binding.textViewDailyPL.text = "${formatCurrency(summary.dailyPL, true)} (${String.format("%+.2f%%", summary.dailyPLPercent)})"
            binding.textViewTotalPL.text = "${formatCurrency(summary.totalPL, true)} (${String.format("%+.2f%%", summary.totalPLPercent)})"

            val dailyColor = if (summary.dailyPL >= 0) R.color.positive_green else R.color.negative_red
            val totalColor = if (summary.totalPL >= 0) R.color.positive_green else R.color.negative_red

            binding.textViewDailyPL.setTextColor(ContextCompat.getColor(itemView.context, dailyColor))
            binding.textViewTotalPL.setTextColor(ContextCompat.getColor(itemView.context, totalColor))
        }
    }

    class PortfolioDiffCallback : DiffUtil.ItemCallback<PortfolioSummary>() {
        override fun areItemsTheSame(oldItem: PortfolioSummary, newItem: PortfolioSummary): Boolean {
            return oldItem.portfolio.id == newItem.portfolio.id
        }

        override fun areContentsTheSame(oldItem: PortfolioSummary, newItem: PortfolioSummary): Boolean {
            return oldItem == newItem
        }
    }
}
