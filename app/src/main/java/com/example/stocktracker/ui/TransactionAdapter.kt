package com.example.stocktracker.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.stocktracker.R
import com.example.stocktracker.data.Transaction
import com.example.stocktracker.data.TransactionType
import com.example.stocktracker.databinding.ListItemTransactionBinding
import com.example.stocktracker.ui.components.formatCurrency
import java.time.format.DateTimeFormatter

class TransactionAdapter(private val onItemClicked: (Transaction) -> Unit) :
    ListAdapter<Transaction, TransactionAdapter.TransactionViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TransactionViewHolder {
        val binding = ListItemTransactionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TransactionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TransactionViewHolder, position: Int) {
        val current = getItem(position)
        holder.itemView.setOnClickListener {
            onItemClicked(current)
        }
        holder.bind(current)
    }

    class TransactionViewHolder(private val binding: ListItemTransactionBinding) :
        RecyclerView.ViewHolder(binding.root) {
        private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        fun bind(transaction: Transaction) {
            binding.textViewDate.text = transaction.date.format(dateFormatter)
            binding.textViewQuantity.text = transaction.quantity.toString()
            binding.textViewPrice.text = transaction.price.toString()
            binding.textViewAmount.text = formatCurrency(transaction.quantity * transaction.price, false)

            when (transaction.type) {
                TransactionType.BUY -> {
                    binding.textViewType.text = "买入"
                    binding.textViewType.setTextColor(ContextCompat.getColor(itemView.context, R.color.positive_green))
                }
                TransactionType.SELL -> {
                    binding.textViewType.text = "卖出"
                    binding.textViewType.setTextColor(ContextCompat.getColor(itemView.context, R.color.negative_red))
                }
                TransactionType.DIVIDEND -> {
                    binding.textViewType.text = "分红"
                    binding.textViewType.setTextColor(ContextCompat.getColor(itemView.context, R.color.colorPrimary))
                }
            }
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<Transaction>() {
            override fun areItemsTheSame(oldItem: Transaction, newItem: Transaction): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: Transaction, newItem: Transaction): Boolean {
                return oldItem == newItem
            }
        }
    }
}
