package com.aerolite.stocktracker.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aerolite.stocktracker.R
import com.aerolite.stocktracker.data.PortfolioSummary
import com.aerolite.stocktracker.databinding.ListItemPortfolioBinding
import com.aerolite.stocktracker.ui.components.formatCurrency

class PortfolioListAdapter(
    private val onPortfolioClick: (PortfolioSummary) -> Unit,
    private val onDeleteClick: (PortfolioSummary) -> Unit
) : ListAdapter<PortfolioSummary, PortfolioListAdapter.PortfolioViewHolder>(PortfolioDiffCallback()) {

    private var openedPosition: Int = RecyclerView.NO_POSITION

    fun setOpenedPosition(position: Int) {
        if (openedPosition == position) return
        val prev = openedPosition
        openedPosition = position
        if (prev != RecyclerView.NO_POSITION) notifyItemChanged(prev, PAYLOAD_REVEAL)
        if (openedPosition != RecyclerView.NO_POSITION) notifyItemChanged(openedPosition, PAYLOAD_REVEAL)
    }

    fun getOpenedPosition() = openedPosition

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PortfolioViewHolder {
        val binding = ListItemPortfolioBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PortfolioViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PortfolioViewHolder, position: Int) {
        holder.bind(getItem(position), position == openedPosition)
    }

    override fun onBindViewHolder(holder: PortfolioViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_REVEAL)) {
            holder.updateRevealState(position == openedPosition)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class PortfolioViewHolder(val binding: ListItemPortfolioBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.viewForeground.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    if (openedPosition != RecyclerView.NO_POSITION) {
                        setOpenedPosition(RecyclerView.NO_POSITION)
                    } else {
                        onPortfolioClick(getItem(position))
                    }
                }
            }
            binding.buttonDelete.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onDeleteClick(getItem(position))
                }
            }
        }

        fun updateRevealState(isOpened: Boolean) {
            val buttonWidth = itemView.resources.getDimensionPixelSize(R.dimen.delete_button_width).toFloat()
            val revealDistance = buttonWidth + 12f
            
            if (isOpened) {
                binding.viewForeground.translationX = -revealDistance
                binding.buttonDeleteCard.translationX = 0f
                binding.buttonDeleteCard.alpha = 1f
            } else {
                binding.viewForeground.translationX = 0f
                binding.buttonDeleteCard.translationX = revealDistance
                binding.buttonDeleteCard.alpha = 0f
            }
        }

        fun bind(summary: PortfolioSummary, isOpened: Boolean) {
            updateRevealState(isOpened)

            binding.textViewPortfolioName.text = summary.portfolio.name
            binding.textViewTotalValue.text = "$${formatCurrency(summary.totalMarketValue, false)}"

            binding.textViewDailyPL.text = "${formatCurrency(summary.dailyPL, true)} (${String.format("%+.2f%%", summary.dailyPLPercent)})"
            binding.textViewTotalPL.text = "${formatCurrency(summary.totalPL, true)} (${String.format("%+.2f%%", summary.totalPLPercent)})"

            val dailyColor = if (summary.dailyPL >= 0) R.color.positive_green else R.color.negative_red
            val totalColor = if (summary.totalPL >= 0) R.color.positive_green else R.color.negative_red

            binding.textViewDailyPL.setTextColor(ContextCompat.getColor(itemView.context, dailyColor))
            binding.textViewTotalPL.setTextColor(ContextCompat.getColor(itemView.context, totalColor))
        }
    }

    companion object {
        private const val PAYLOAD_REVEAL = "PAYLOAD_REVEAL"
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
