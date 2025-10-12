package com.example.stocktracker.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.stocktracker.R
import com.example.stocktracker.data.StockHolding
import com.example.stocktracker.databinding.*
import com.example.stocktracker.ui.components.ChartSegment
import com.example.stocktracker.ui.components.formatCurrency
import com.example.stocktracker.ui.screens.PortfolioListItem
import com.example.stocktracker.ui.viewmodel.StockUiState
import kotlin.math.absoluteValue

// --- View Holder Types ---
private const val ITEM_VIEW_TYPE_HEADER = 0
private const val ITEM_VIEW_TYPE_CHART = 1
private const val ITEM_VIEW_TYPE_STOCK = 2


class PortfolioAdapter(
    private val onStockClicked: (StockHolding) -> Unit
) : ListAdapter<PortfolioListItem, RecyclerView.ViewHolder>(PortfolioDiffCallback()) {

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is PortfolioListItem.Header -> ITEM_VIEW_TYPE_HEADER
            is PortfolioListItem.Chart -> ITEM_VIEW_TYPE_CHART
            is PortfolioListItem.Stock -> ITEM_VIEW_TYPE_STOCK
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            ITEM_VIEW_TYPE_HEADER -> HeaderViewHolder.from(parent)
            ITEM_VIEW_TYPE_CHART -> ChartViewHolder.from(parent)
            ITEM_VIEW_TYPE_STOCK -> StockViewHolder.from(parent)
            else -> throw ClassCastException("Unknown viewType $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderViewHolder -> {
                val headerItem = getItem(position) as PortfolioListItem.Header
                holder.bind(headerItem.uiState)
            }
            is ChartViewHolder -> {
                val chartItem = getItem(position) as PortfolioListItem.Chart
                holder.bind(chartItem.holdings)
            }
            is StockViewHolder -> {
                val stockItem = getItem(position) as PortfolioListItem.Stock
                holder.bind(stockItem.stock, onStockClicked)
            }
        }
    }

    // --- ViewHolders ---

    class HeaderViewHolder(private val binding: ListItemPortfolioHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(uiState: StockUiState) {
            val holdings = uiState.holdings
            val totalMarketValue = holdings.sumOf { it.marketValue }
            val totalDailyPL = holdings.sumOf { it.dailyPL }
            val totalHoldingPL = holdings.sumOf { it.holdingPL }
            val totalPL = holdings.sumOf { it.totalPL }
            val cash = uiState.cashBalance

            val totalDailyPLPercent = if (totalMarketValue - totalDailyPL != 0.0) (totalDailyPL / (totalMarketValue - totalDailyPL)) * 100 else 0.0
            val totalHoldingPLPercent = if (totalMarketValue - totalHoldingPL != 0.0) (totalHoldingPL / (totalMarketValue - totalHoldingPL)) * 100 else 0.0
            val totalPLPercent = if (totalMarketValue - totalPL != 0.0) (totalPL / (totalMarketValue - totalPL)) * 100 else 0.0

            binding.header.textViewMarketValue.text = formatCurrency(totalMarketValue, false)

            // 当日盈亏
            binding.header.metricDailyPl.metricLabel.text = "当日盈亏"
            binding.header.metricDailyPl.metricValue.text = formatCurrency(totalDailyPL, false)
            binding.header.metricDailyPl.metricPercent.text = String.format("%.2f%%", totalDailyPLPercent)
            updateMetricColor(binding.header.metricDailyPl.metricValue, binding.header.metricDailyPl.metricPercent, totalDailyPL)

            // 持仓盈亏
            binding.header.metricHoldingPl.metricLabel.text = "持仓盈亏"
            binding.header.metricHoldingPl.metricValue.text = formatCurrency(totalHoldingPL, false)
            binding.header.metricHoldingPl.metricPercent.text = String.format("%.2f%%", totalHoldingPLPercent)
            updateMetricColor(binding.header.metricHoldingPl.metricValue, binding.header.metricHoldingPl.metricPercent, totalHoldingPL)

            // 总盈亏
            binding.header.metricTotalPl.metricLabel.text = "总盈亏"
            binding.header.metricTotalPl.metricValue.text = formatCurrency(totalPL, true)
            binding.header.metricTotalPl.metricPercent.text = String.format("%s%.2f%%", if(totalPL >= 0) "+" else "", totalPLPercent.absoluteValue)
            updateMetricColor(binding.header.metricTotalPl.metricValue, binding.header.metricTotalPl.metricPercent, totalPL)

            // 现金
            binding.header.metricCash.metricLabel.text = "现金"
            binding.header.metricCash.metricValue.text = formatCurrency(cash, false)
            binding.header.metricCash.metricValue.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.white))
            binding.header.metricCash.metricPercent.visibility = View.GONE
        }
        private fun updateMetricColor(valueView: TextView, percentView: TextView, value: Double) {
            val color = if (value >= 0) {
                ContextCompat.getColor(itemView.context, R.color.positive_green)
            } else {
                ContextCompat.getColor(itemView.context, R.color.negative_red)
            }
            valueView.setTextColor(color)
            percentView.setTextColor(color)
        }
        companion object {
            fun from(parent: ViewGroup): HeaderViewHolder {
                val layoutInflater = LayoutInflater.from(parent.context)
                val binding = ListItemPortfolioHeaderBinding.inflate(layoutInflater, parent, false)
                return HeaderViewHolder(binding)
            }
        }
    }

    class ChartViewHolder(private val binding: ListItemPortfolioChartBinding) : RecyclerView.ViewHolder(binding.root) {
        private val context = itemView.context
        private val chartColors by lazy {
            listOf(
                R.color.chartColor1, R.color.chartColor2, R.color.chartColor3, R.color.chartColor4, R.color.chartColor5,
                R.color.chartColor6, R.color.chartColor7, R.color.chartColor8, R.color.chartColor9, R.color.chartColor10
            )
        }

        fun bind(holdings: List<StockHolding>) {
            val totalMarketValue = holdings.sumOf { it.marketValue }
            if (totalMarketValue <= 0) return

            // *** 关键修复：合并为“其他”的逻辑 ***
            val holdingsForChart: List<Pair<String, Double>>
            if (holdings.size > 4) {
                val top4 = holdings.sortedByDescending { it.marketValue }.take(4)
                val othersValue = holdings.drop(4).sumOf { it.marketValue }
                holdingsForChart = top4.map { Pair(it.name, it.marketValue) } + Pair("其他", othersValue)
            } else {
                holdingsForChart = holdings.map { Pair(it.name, it.marketValue) }
            }

            val segments = holdingsForChart.mapIndexed { index, data ->
                ChartSegment(
                    data.second.toFloat(),
                    chartColors[index % chartColors.size]
                )
            }
            binding.donutChart.setData(segments)
            updateLegend(holdingsForChart, totalMarketValue)
        }

        private fun updateLegend(holdingsData: List<Pair<String, Double>>, totalMarketValue: Double) {
            binding.legendLayout.removeAllViews()
            holdingsData.forEachIndexed { index, data ->
                val percentage = (data.second / totalMarketValue) * 100
                val legendItem = createLegendItem(
                    colorResId = chartColors[index % chartColors.size],
                    name = data.first,
                    percentage = percentage
                )
                binding.legendLayout.addView(legendItem)
            }
        }

        private fun createLegendItem(colorResId: Int, name: String, percentage: Double): View {
            val inflater = LayoutInflater.from(context)
            val itemBinding = ListItemLegendBinding.inflate(inflater, null, false)
            itemBinding.indicator.background.setTint(ContextCompat.getColor(context, colorResId))
            itemBinding.textViewName.text = name
            itemBinding.textViewPercentage.text = String.format("%.2f%%", percentage)
            return itemBinding.root
        }

        companion object {
            fun from(parent: ViewGroup): ChartViewHolder {
                val layoutInflater = LayoutInflater.from(parent.context)
                val binding = ListItemPortfolioChartBinding.inflate(layoutInflater, parent, false)
                return ChartViewHolder(binding)
            }
        }
    }

    class StockViewHolder(private val binding: ListItemStockBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(stock: StockHolding, onStockClicked: (StockHolding) -> Unit) {
            itemView.setOnClickListener { onStockClicked(stock) }

            binding.textViewName.text = stock.name
            binding.textViewTicker.text = stock.ticker
            binding.textViewMarketValue.text = formatCurrency(stock.marketValue, false)
            binding.textViewTotalCost.text = "(USD)${formatCurrency(stock.totalCost - stock.totalSoldValue, false)}"
            binding.textViewDailyPlValue.text = formatCurrency(stock.dailyPL, true)
            binding.textViewDailyPlPercent.text = String.format("%.2f%%", stock.dailyPLPercent)
            updatePlColor(binding.textViewDailyPlValue, binding.textViewDailyPlPercent, stock.dailyPL)
            binding.textViewTotalPlValue.text = formatCurrency(stock.totalPL, true)
            binding.textViewTotalPlPercent.text = String.format("%.2f%%", stock.totalPLPercent)
            updatePlColor(binding.textViewTotalPlValue, binding.textViewTotalPlPercent, stock.totalPL)
        }

        private fun updatePlColor(valueView: TextView, percentView: TextView, value: Double) {
            val color = if (value >= 0) {
                ContextCompat.getColor(itemView.context, R.color.positive_green)
            } else {
                ContextCompat.getColor(itemView.context, R.color.negative_red)
            }
            valueView.setTextColor(color)
            percentView.setTextColor(color)
        }

        companion object {
            fun from(parent: ViewGroup): StockViewHolder {
                val layoutInflater = LayoutInflater.from(parent.context)
                val binding = ListItemStockBinding.inflate(layoutInflater, parent, false)
                return StockViewHolder(binding)
            }
        }
    }
}

// --- DiffUtil Callback ---

class PortfolioDiffCallback : DiffUtil.ItemCallback<PortfolioListItem>() {
    override fun areItemsTheSame(oldItem: PortfolioListItem, newItem: PortfolioListItem): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: PortfolioListItem, newItem: PortfolioListItem): Boolean {
        return oldItem == newItem
    }
}

