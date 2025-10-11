package com.example.stocktracker.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.stocktracker.R
import com.example.stocktracker.data.StockHolding
import com.example.stocktracker.databinding.ListItemStockBinding
import com.example.stocktracker.ui.components.formatCurrency
import java.text.DecimalFormat

class StockAdapter(private val onItemClicked: (StockHolding) -> Unit) :
    ListAdapter<StockHolding, StockAdapter.StockViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StockViewHolder {
        val binding = ListItemStockBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return StockViewHolder(binding)
    }

    override fun onBindViewHolder(holder: StockViewHolder, position: Int) {
        val current = getItem(position)
        holder.itemView.setOnClickListener {
            onItemClicked(current)
        }
        holder.bind(current)
    }

    class StockViewHolder(private val binding: ListItemStockBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(stock: StockHolding) {
            binding.textViewName.text = stock.name
            binding.textViewTicker.text = stock.ticker
            binding.textViewMarketValue.text = formatCurrency(stock.marketValue, showSign = false)

            val plText = "${
                formatCurrency(
                    stock.totalPL,
                    showSign = true
                )
            } (${DecimalFormat("#.##").format(stock.totalPLPercent)}%)"


            val plColor = if (stock.totalPL >= 0) {
                ContextCompat.getColor(itemView.context, R.color.positive_green)
            } else {
                ContextCompat.getColor(itemView.context, R.color.negative_red)
            }

        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<StockHolding>() {
            override fun areItemsTheSame(oldItem: StockHolding, newItem: StockHolding): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: StockHolding, newItem: StockHolding): Boolean {
                return oldItem == newItem
            }
        }
    }
}