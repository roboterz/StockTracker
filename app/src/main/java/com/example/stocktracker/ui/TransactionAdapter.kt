package com.example.stocktracker.ui

import android.view.LayoutInflater
import android.view.View
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
import java.text.DecimalFormat
import java.time.format.DateTimeFormatter
import java.util.Locale

class TransactionAdapter(private val onItemClicked: (Transaction) -> Unit) :
    ListAdapter<Transaction, TransactionAdapter.TransactionViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TransactionViewHolder {
        val binding = ListItemTransactionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TransactionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TransactionViewHolder, position: Int) {
        val current = getItem(position)
        holder.itemView.setOnClickListener {
            // 只有买卖和分红记录可以编辑
            if (current.type != TransactionType.SPLIT) {
                onItemClicked(current)
            }
        }
        holder.bind(current)
    }

    class TransactionViewHolder(private val binding: ListItemTransactionBinding) :
        RecyclerView.ViewHolder(binding.root) {
        private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        fun bind(transaction: Transaction) {
            binding.textViewDate.text = transaction.date.format(dateFormatter)

            // 重置视图可见性
            binding.textViewQuantity.visibility = View.VISIBLE
            binding.layoutAmount.visibility = View.VISIBLE
            binding.textViewType.textAlignment = View.TEXT_ALIGNMENT_INHERIT

            when (transaction.type) {
                TransactionType.BUY -> {
                    binding.textViewType.text = "买入"
                    binding.textViewType.setTextColor(ContextCompat.getColor(itemView.context, R.color.positive_green))
                    binding.textViewQuantity.text = DecimalFormat("#.##").format(transaction.quantity).toString()
                    binding.textViewPrice.text = DecimalFormat("#.####").format(transaction.price)  //String.format(Locale.US, "%.3f", transaction.price)
                    binding.textViewAmount.text = formatCurrency(transaction.quantity * transaction.price, false)
                    binding.textViewAmount.setTextColor(ContextCompat.getColor(itemView.context, R.color.positive_green))
                }
                TransactionType.SELL -> {
                    binding.textViewType.text = "卖出"
                    binding.textViewType.setTextColor(ContextCompat.getColor(itemView.context, R.color.negative_red))
                    binding.textViewQuantity.text = DecimalFormat("#.##").format(transaction.quantity).toString()
                    binding.textViewPrice.text = DecimalFormat("#.####").format(transaction.price)  // String.format(Locale.US, "%.3f", transaction.price)
                    binding.textViewAmount.text = formatCurrency(transaction.quantity * transaction.price, false)
                    binding.textViewAmount.setTextColor(ContextCompat.getColor(itemView.context, R.color.negative_red))
                }
                TransactionType.DIVIDEND -> {
                    binding.textViewType.text = "分红"
                    binding.textViewType.setTextColor(ContextCompat.getColor(itemView.context, R.color.dividend_gray))
                    binding.textViewQuantity.text = DecimalFormat("#.##").format(transaction.quantity).toString()
                    binding.textViewPrice.text = String.format(Locale.US, "%.4f/股", transaction.price) // 每股分红
                    binding.textViewAmount.text = formatCurrency(transaction.quantity * transaction.price, false)
                    binding.textViewAmount.setTextColor(ContextCompat.getColor(itemView.context, R.color.dividend_gray))
                }
                TransactionType.SPLIT -> {
                    val numerator = DecimalFormat("#.##").format(transaction.quantity)
                    val denominator = transaction.price.toInt()
                    val ratio = numerator.toDouble() / denominator

                    binding.textViewType.text = if (ratio > 1) "${ numerator}:${denominator} 拆股" else "${numerator}:${denominator} 合股"
                    binding.textViewType.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.white))
                    binding.textViewType.textAlignment = View.TEXT_ALIGNMENT_CENTER

                    // 隐藏数量和价格/金额列
                    binding.textViewQuantity.visibility = View.INVISIBLE
                    binding.layoutAmount.visibility = View.INVISIBLE
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

