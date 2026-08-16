package com.aerolite.stocktracker.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aerolite.stocktracker.R
import com.aerolite.stocktracker.data.Transaction
import com.aerolite.stocktracker.data.TransactionType
import com.aerolite.stocktracker.databinding.ListItemTransactionBinding
import com.aerolite.stocktracker.ui.components.formatCurrency
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
            onItemClicked(current)
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
                    binding.textViewType.text = itemView.context.getString(R.string.transaction_buy)
                    binding.textViewType.setTextColor(ContextCompat.getColor(itemView.context, R.color.positive_green))
                    binding.textViewQuantity.text = DecimalFormat("#.##").format(transaction.quantity).toString()
                    binding.textViewPrice.text = DecimalFormat("#.#####").format(transaction.price)  //String.format(Locale.US, "%.3f", transaction.price)
                    binding.textViewAmount.text = formatCurrency(transaction.quantity * transaction.price, false)
                    binding.textViewAmount.setTextColor(ContextCompat.getColor(itemView.context, R.color.positive_green))
                }
                TransactionType.SELL -> {
                    binding.textViewType.text = itemView.context.getString(R.string.transaction_sell)
                    binding.textViewType.setTextColor(ContextCompat.getColor(itemView.context, R.color.negative_red))
                    binding.textViewQuantity.text = DecimalFormat("#.##").format(transaction.quantity).toString()
                    binding.textViewPrice.text = DecimalFormat("#.#####").format(transaction.price)  // String.format(Locale.US, "%.3f", transaction.price)
                    binding.textViewAmount.text = formatCurrency(transaction.quantity * transaction.price, false)
                    binding.textViewAmount.setTextColor(ContextCompat.getColor(itemView.context, R.color.negative_red))
                }
                TransactionType.DIVIDEND -> {
                    binding.textViewType.text = itemView.context.getString(R.string.transaction_dividend)
                    binding.textViewType.setTextColor(ContextCompat.getColor(itemView.context, R.color.dividend_gray))
                    binding.textViewQuantity.text = DecimalFormat("#.##").format(transaction.quantity).toString()
                    binding.textViewPrice.text = String.format(Locale.US, itemView.context.getString(R.string.dividend_per_share_suffix), transaction.price) // 每股分红
                    binding.textViewAmount.text = formatCurrency(transaction.quantity * transaction.price, false)
                    binding.textViewAmount.setTextColor(ContextCompat.getColor(itemView.context, R.color.dividend_gray))
                }
                TransactionType.SPLIT -> {
                    val numerator = DecimalFormat("#.##").format(transaction.quantity)
                    val denominator = DecimalFormat("#.##").format(transaction.price)
                    val ratio = transaction.quantity / transaction.price

                    binding.textViewType.text = if (ratio > 1) {
                        itemView.context.getString(R.string.transaction_split_desc, numerator, denominator)
                    } else {
                        itemView.context.getString(R.string.transaction_reverse_split_desc, numerator, denominator)
                    }
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

