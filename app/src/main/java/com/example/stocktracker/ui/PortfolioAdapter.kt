package com.example.stocktracker.ui

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.stocktracker.R
import com.example.stocktracker.data.CashTransaction
import com.example.stocktracker.data.CashTransactionType
import com.example.stocktracker.data.StockHolding
import com.example.stocktracker.databinding.*
import com.example.stocktracker.ui.components.ChartSegment
import com.example.stocktracker.ui.components.LineData
import com.example.stocktracker.ui.components.formatCurrency
import com.example.stocktracker.ui.screens.AssetType
import com.example.stocktracker.ui.screens.PortfolioListItem
import com.example.stocktracker.ui.viewmodel.StockUiState
import com.example.stocktracker.ui.viewmodel.TimeRange
import java.text.DecimalFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.absoluteValue

private const val ITEM_VIEW_TYPE_HEADER = 0
private const val ITEM_VIEW_TYPE_PROFIT_LOSS_CHART = 1
private const val ITEM_VIEW_TYPE_CHART = 2
private const val ITEM_VIEW_TYPE_STOCK_HEADER = 3
private const val ITEM_VIEW_TYPE_STOCK = 4
private const val ITEM_VIEW_TYPE_CLOSED_HEADER = 5
private const val ITEM_VIEW_TYPE_CLOSED_POSITION = 6
private const val ITEM_VIEW_TYPE_CASH_HEADER = 7
private const val ITEM_VIEW_TYPE_CASH_TRANSACTION = 8


class PortfolioAdapter(
    private val onStockClicked: (StockHolding) -> Unit,
    private val onHoldingsClicked: () -> Unit,
    private val onClosedClicked: () -> Unit,
    private val onCashClicked: () -> Unit,
    private val onCashItemClicked: (CashTransaction) -> Unit,
    private val onTimeRangeSelected: (TimeRange) -> Unit
) : ListAdapter<PortfolioListItem, RecyclerView.ViewHolder>(PortfolioDiffCallback()) {

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is PortfolioListItem.Header -> ITEM_VIEW_TYPE_HEADER
            is PortfolioListItem.ProfitLossChart -> ITEM_VIEW_TYPE_PROFIT_LOSS_CHART
            is PortfolioListItem.Chart -> ITEM_VIEW_TYPE_CHART
            is PortfolioListItem.StockHeader -> ITEM_VIEW_TYPE_STOCK_HEADER
            is PortfolioListItem.Stock -> ITEM_VIEW_TYPE_STOCK
            is PortfolioListItem.ClosedPositionHeader -> ITEM_VIEW_TYPE_CLOSED_HEADER
            is PortfolioListItem.ClosedPosition -> ITEM_VIEW_TYPE_CLOSED_POSITION
            is PortfolioListItem.CashHeader -> ITEM_VIEW_TYPE_CASH_HEADER
            is PortfolioListItem.Cash -> ITEM_VIEW_TYPE_CASH_TRANSACTION
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            ITEM_VIEW_TYPE_HEADER -> HeaderViewHolder.from(parent)
            ITEM_VIEW_TYPE_PROFIT_LOSS_CHART -> ProfitLossChartViewHolder.from(parent, onTimeRangeSelected)
            ITEM_VIEW_TYPE_CHART -> ChartViewHolder.from(parent, onHoldingsClicked, onClosedClicked, onCashClicked)
            ITEM_VIEW_TYPE_STOCK_HEADER -> StockHeaderViewHolder.from(parent)
            ITEM_VIEW_TYPE_STOCK -> StockViewHolder.from(parent)
            ITEM_VIEW_TYPE_CLOSED_HEADER -> ClosedPositionHeaderViewHolder.from(parent)
            ITEM_VIEW_TYPE_CLOSED_POSITION -> ClosedPositionViewHolder.from(parent)
            ITEM_VIEW_TYPE_CASH_HEADER -> CashHeaderViewHolder.from(parent)
            ITEM_VIEW_TYPE_CASH_TRANSACTION -> CashTransactionViewHolder.from(parent)
            else -> throw ClassCastException("Unknown viewType $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderViewHolder -> {
                val headerItem = getItem(position) as PortfolioListItem.Header
                holder.bind(headerItem.uiState)
            }
            is ProfitLossChartViewHolder -> {
                val plChartItem = getItem(position) as PortfolioListItem.ProfitLossChart
                // *** 修改：传入 benchmarkData ***
                holder.bind(plChartItem.chartData, plChartItem.benchmarkData, plChartItem.selectedRange, plChartItem.isLoading)
            }
            is ChartViewHolder -> {
                val chartItem = getItem(position) as PortfolioListItem.Chart
                holder.bind(chartItem.holdings, chartItem.selectedType)
            }
            is StockViewHolder -> {
                val stockItem = getItem(position) as PortfolioListItem.Stock
                holder.bind(stockItem.stock, onStockClicked)
            }
            is StockHeaderViewHolder -> holder.bind()
            is ClosedPositionHeaderViewHolder -> holder.bind()
            is ClosedPositionViewHolder -> {
                val closedItem = getItem(position) as PortfolioListItem.ClosedPosition
                holder.bind(closedItem.stock, onStockClicked)
            }
            is CashHeaderViewHolder -> holder.bind()
            is CashTransactionViewHolder -> {
                val cashItem = getItem(position) as PortfolioListItem.Cash
                holder.bind(cashItem.transaction, onCashItemClicked)
            }
        }
    }

    class HeaderViewHolder(private val binding: ListItemPortfolioHeaderBinding) : RecyclerView.ViewHolder(binding.root) {

        @SuppressLint("SetTextI18n")
        fun bind(uiState: StockUiState) {
            val holdings = uiState.holdings
            val closedPositions = uiState.closedPositions

            val totalMarketValue = holdings.sumOf { it.marketValue }
            val totalDailyPL = holdings.sumOf { it.dailyPL }
            val totalHoldingPL = holdings.sumOf { it.holdingPL }

            val activeTotalPL = holdings.sumOf { it.totalPL }
            val closedTotalPL = closedPositions.sumOf { it.totalPL }
            val totalPL = activeTotalPL + closedTotalPL

            val cash = uiState.cashBalance
            val totalCost = holdings.sumOf { it.totalCost }

            val totalDailyPLPercent = if (totalMarketValue - totalDailyPL != 0.0) (totalDailyPL / (totalMarketValue - totalDailyPL)) * 100 else 0.0
            val totalHoldingPLPercent = if (totalCost > 0) (totalHoldingPL / totalCost) * 100 else 0.0
            val totalPLPercent = if (totalCost > 0) (totalPL / totalCost) * 100 else 0.0

            binding.header.textViewMarketValue.text = formatCurrency(totalMarketValue, false)

            binding.header.metricDailyPl.metricLabel.text = "当日盈亏"
            binding.header.metricDailyPl.metricValue.text = formatCurrency(totalDailyPL, true)
            binding.header.metricDailyPl.metricPercent.text = "${formatCurrency(totalDailyPLPercent, true)}%"
            updateMetricColor(binding.header.metricDailyPl.metricValue, binding.header.metricDailyPl.metricPercent, totalDailyPL)

            binding.header.metricHoldingPl.metricLabel.text = "持仓盈亏"
            binding.header.metricHoldingPl.metricValue.text = formatCurrency(totalHoldingPL, true)
            binding.header.metricHoldingPl.metricPercent.text = "${formatCurrency(totalHoldingPLPercent, true)}%"
            updateMetricColor(binding.header.metricHoldingPl.metricValue, binding.header.metricHoldingPl.metricPercent, totalHoldingPL)

            binding.header.metricTotalPl.metricLabel.text = "总盈亏"
            binding.header.metricTotalPl.metricValue.text = formatCurrency(totalPL, true)
            binding.header.metricTotalPl.metricPercent.text = "${formatCurrency(totalPLPercent, true)}%"
            updateMetricColor(binding.header.metricTotalPl.metricValue, binding.header.metricTotalPl.metricPercent, totalPL)

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

    class ProfitLossChartViewHolder(
        private val binding: ListItemPortfolioPlChartBinding,
        private val onTimeRangeSelected: (TimeRange) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private val timeRangeButtons: Map<TimeRange, Button>
        private var listenersAreSet = false
        private val mainLineColor = ContextCompat.getColor(itemView.context, R.color.chartLineBlue)
        private val benchmarkLineColor = ContextCompat.getColor(itemView.context, R.color.chartLineOrange) // 使用橙色作为基准色
        private val dateLabels: List<TextView>
        private val yearMonthFmt = DateTimeFormatter.ofPattern("yyyy-MM")
        private val monthDayFmt = DateTimeFormatter.ofPattern("MM-dd")

        init {
            timeRangeButtons = mapOf(
                TimeRange.FIVE_DAY to binding.button5d,
                TimeRange.ONE_MONTH to binding.button1m,
                TimeRange.THREE_MONTH to binding.button3m,
                TimeRange.SIX_MONTH to binding.button6m,
                TimeRange.ONE_YEAR to binding.button1y,
                TimeRange.FIVE_YEAR to binding.button5y,
                TimeRange.ALL to binding.buttonAll
            )

            dateLabels = listOf(binding.dateStart, binding.dateMidLeft, binding.dateMid, binding.dateMidRight, binding.dateEnd)
        }

        fun bind(
            chartData: List<StockUiState.ChartDataPoint>,
            // *** 新增：接收基准数据 ***
            benchmarkData: List<StockUiState.ChartDataPoint>,
            selectedRange: TimeRange,
            isLoading: Boolean
        ) {
            if (!listenersAreSet) {
                timeRangeButtons.forEach { (range, button) ->
                    button.setOnClickListener {
                        onTimeRangeSelected(range)
                    }
                }
                listenersAreSet = true
            }

            updateTimeRangeButtonTints(selectedRange)

            binding.plChartProgressBar.isVisible = isLoading
            binding.plLineChart.isVisible = !isLoading && chartData.isNotEmpty()

            if (!isLoading && chartData.isNotEmpty()) {
                // *** 传入两组数据 ***
                updateChart(chartData, benchmarkData)
            } else if (!isLoading) {
                binding.plLineChart.setData(emptyList())
                updateChartLabels(null, null, selectedRange)
                updateChartMinMax(emptyList())
            }
        }

        private fun updateTimeRangeButtonTints(selectedRange: TimeRange) {
            val selectedColor = ColorStateList.valueOf(Color.parseColor("#2689FE"))
            val defaultColor = ColorStateList.valueOf(Color.TRANSPARENT)

            timeRangeButtons.forEach { (range, button) ->
                button.backgroundTintList = if (range == selectedRange) selectedColor else defaultColor
            }
        }

        private fun updateChart(
            portfolioData: List<StockUiState.ChartDataPoint>,
            benchmarkData: List<StockUiState.ChartDataPoint>
        ) {
            // 1. 准备多条曲线数据
            val portfolioPoints = portfolioData.map { it.value.toFloat() }
            val benchmarkPoints = benchmarkData.map { it.value.toFloat() }

            val lineDataList = mutableListOf<LineData>()
            // 主曲线 (投资组合)
            lineDataList.add(LineData(portfolioPoints, mainLineColor))
            // 基准曲线 (NASDAQ)
            if (benchmarkPoints.isNotEmpty()) {
                lineDataList.add(LineData(benchmarkPoints, benchmarkLineColor))
            }

            binding.plLineChart.setData(lineDataList)

            // 2. 更新 Y 轴 (Min/Max/Mid) - 综合两条曲线的极值
            val allPoints = portfolioPoints + benchmarkPoints
            updateChartMinMax(allPoints)

            // 3. 更新 X 轴 (日期标签)
            val startDate = portfolioData.firstOrNull()?.date
            val endDate = portfolioData.lastOrNull()?.date
            updateChartLabels(startDate, endDate, timeRangeButtons.entries.find { it.value.backgroundTintList == ColorStateList.valueOf(Color.parseColor("#2689FE")) }?.key ?: TimeRange.ONE_MONTH)
        }

        private fun updateChartMinMax(points: List<Float>) {
            if (points.isNotEmpty()) {
                val maxVal = points.maxOrNull()!!
                val minVal = points.minOrNull()!!
                val midVal = (maxVal + minVal) / 2
                binding.plChartPercentMax.text = String.format("%+.2f%%", maxVal)
                binding.plChartPercentMid.text = String.format("%+.2f%%", midVal)
                binding.plChartPercentMin.text = String.format("%+.2f%%", minVal)
            } else {
                binding.plChartPercentMax.text = ""
                binding.plChartPercentMid.text = ""
                binding.plChartPercentMin.text = ""
            }
        }

        private fun updateChartLabels(startDate: LocalDate?, endDate: LocalDate?, range: TimeRange) {
            dateLabels.forEach { it.visibility = View.INVISIBLE }

            if (startDate == null || endDate == null) {
                dateLabels.first().text = "无数据"
                dateLabels.first().visibility = View.VISIBLE
                return
            }

            val formatter = if (range == TimeRange.FIVE_DAY || range == TimeRange.ONE_MONTH || range == TimeRange.THREE_MONTH) {
                monthDayFmt
            } else {
                yearMonthFmt
            }

            binding.dateStart.text = startDate.format(formatter)
            binding.dateMid.text = ""
            binding.dateEnd.text = endDate.format(formatter)

            binding.dateStart.visibility = View.VISIBLE
            binding.dateEnd.visibility = View.VISIBLE
        }


        companion object {
            fun from(parent: ViewGroup, onTimeRangeSelected: (TimeRange) -> Unit): ProfitLossChartViewHolder {
                val layoutInflater = LayoutInflater.from(parent.context)
                val binding = ListItemPortfolioPlChartBinding.inflate(layoutInflater, parent, false)
                return ProfitLossChartViewHolder(binding, onTimeRangeSelected)
            }
        }
    }

    class ChartViewHolder(
        private val binding: ListItemPortfolioChartBinding,
        private val onHoldingsClicked: () -> Unit,
        private val onClosedClicked: () -> Unit,
        private val onCashClicked: () -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        private val context = itemView.context
        private val chartColors by lazy {
            listOf(
                R.color.chartColor1, R.color.chartColor2, R.color.chartColor3, R.color.chartColor4, R.color.chartColor5,
                R.color.chartColor6, R.color.chartColor7, R.color.chartColor8, R.color.chartColor9, R.color.chartColor10
            )
        }

        private val assetButtons: List<Button>
        private var listenersAreSet = false

        init {
            assetButtons = listOf(binding.buttonHoldingsDetail, binding.buttonClosedPositionsDetail, binding.buttonCashDetail)
        }

        fun bind(holdings: List<StockHolding>, selectedType: AssetType) {
            if (!listenersAreSet) {
                binding.buttonHoldingsDetail.setOnClickListener { onHoldingsClicked() }
                binding.buttonClosedPositionsDetail.setOnClickListener { onClosedClicked() }
                binding.buttonCashDetail.setOnClickListener { onCashClicked() }
                listenersAreSet = true
            }

            when (selectedType){
                AssetType.HOLDINGS -> {
                    binding.layoutChartContainer.visibility = View.VISIBLE
                }
                AssetType.CLOSED -> {
                    binding.layoutChartContainer.visibility = View.GONE
                }
                AssetType.CASH -> {
                    binding.layoutChartContainer.visibility = View.GONE
                }

            }

            binding.buttonHoldingsDetail.isSelected = selectedType == AssetType.HOLDINGS
            binding.buttonClosedPositionsDetail.isSelected = selectedType == AssetType.CLOSED
            binding.buttonCashDetail.isSelected = selectedType == AssetType.CASH

            val totalMarketValue = holdings.sumOf { it.marketValue }
            if (totalMarketValue <= 0) {
                binding.donutChart.setData(emptyList())
                binding.legendLayout.removeAllViews()
                return
            }

            val sortedHoldings = holdings.sortedByDescending { it.marketValue }
            val holdingsForChart: List<Pair<String, Double>>

            if (sortedHoldings.size > 4) {
                val top4 = sortedHoldings.take(4)
                val othersValue = sortedHoldings.drop(4).sumOf { it.marketValue }
                holdingsForChart = top4.map { Pair(it.name, it.marketValue) } + Pair("其他", othersValue)
            } else {
                holdingsForChart = sortedHoldings.map { Pair(it.name, it.marketValue) }
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
            val df = DecimalFormat("0.######")
            itemBinding.textViewPercentage.text = "${df.format(percentage)}%"
            return itemBinding.root
        }

        companion object {
            fun from(
                parent: ViewGroup,
                onHoldingsClicked: () -> Unit,
                onClosedClicked: () -> Unit,
                onCashClicked: () -> Unit
            ): ChartViewHolder {
                val layoutInflater = LayoutInflater.from(parent.context)
                val binding = ListItemPortfolioChartBinding.inflate(layoutInflater, parent, false)
                return ChartViewHolder(binding, onHoldingsClicked, onClosedClicked, onCashClicked)
            }
        }
    }

    class StockHeaderViewHolder(binding: ListItemStockHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind() { }

        companion object {
            fun from(parent: ViewGroup): StockHeaderViewHolder {
                val layoutInflater = LayoutInflater.from(parent.context)
                val binding = ListItemStockHeaderBinding.inflate(layoutInflater, parent, false)
                return StockHeaderViewHolder(binding)
            }
        }
    }

    class StockViewHolder(private val binding: ListItemStockBinding) : RecyclerView.ViewHolder(binding.root) {
        @SuppressLint("SetTextI18n")
        fun bind(stock: StockHolding, onStockClicked: (StockHolding) -> Unit) {
            itemView.setOnClickListener { onStockClicked(stock) }

            binding.textViewName.text = stock.name
            binding.textViewTicker.text = stock.ticker
            binding.textViewMarketValue.text = formatCurrency(stock.marketValue, false)
            binding.textViewTotalCost.text = "(USD)${formatCurrency(stock.totalCost, false)}"
            binding.textViewDailyPlValue.text = formatCurrency(stock.dailyPL, true)
            binding.textViewDailyPlPercent.text = "${formatCurrency(stock.dailyPLPercent, true)}%"
            updatePlColor(binding.textViewDailyPlValue, binding.textViewDailyPlPercent, stock.dailyPL)
            binding.textViewTotalPlValue.text = formatCurrency(stock.totalPL, true)
            binding.textViewTotalPlPercent.text = "${formatCurrency(stock.totalPLPercent, true)}%"
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

    class ClosedPositionHeaderViewHolder(binding: ListItemClosedPositionHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind() { }

        companion object {
            fun from(parent: ViewGroup): ClosedPositionHeaderViewHolder {
                val layoutInflater = LayoutInflater.from(parent.context)
                val binding = ListItemClosedPositionHeaderBinding.inflate(layoutInflater, parent, false)
                return ClosedPositionHeaderViewHolder(binding)
            }
        }
    }

    class ClosedPositionViewHolder(private val binding: ListItemClosedPositionBinding) : RecyclerView.ViewHolder(binding.root) {
        @SuppressLint("SetTextI18n")
        fun bind(stock: StockHolding, onStockClicked: (StockHolding) -> Unit) {
            itemView.setOnClickListener { onStockClicked(stock) }

            binding.textViewName.text = stock.name
            binding.textViewTicker.text = stock.ticker
            binding.textViewTotalCost.text = formatCurrency(stock.totalCostOfAllBuys, false)

            binding.textViewTotalPlValue.text = formatCurrency(stock.totalPL, true)
            binding.textViewTotalPlPercent.text = "${formatCurrency(stock.totalPLPercent, true)}%"
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
            fun from(parent: ViewGroup): ClosedPositionViewHolder {
                val layoutInflater = LayoutInflater.from(parent.context)
                val binding = ListItemClosedPositionBinding.inflate(layoutInflater, parent, false)
                return ClosedPositionViewHolder(binding)
            }
        }
    }

    class CashHeaderViewHolder(binding: ListItemCashHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind() { }

        companion object {
            fun from(parent: ViewGroup): CashHeaderViewHolder {
                val layoutInflater = LayoutInflater.from(parent.context)
                val binding = ListItemCashHeaderBinding.inflate(layoutInflater, parent, false)
                return CashHeaderViewHolder(binding)
            }
        }
    }

    class CashTransactionViewHolder(private val binding: ListItemCashTransactionBinding) : RecyclerView.ViewHolder(binding.root) {
        private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        @SuppressLint("SetTextI1G")
        fun bind(transaction: com.example.stocktracker.data.CashTransaction, onCashItemClicked: (CashTransaction) -> Unit) {
            itemView.setOnClickListener {
                if (transaction.stockTransactionId == null) {
                    onCashItemClicked(transaction)
                }
            }

            binding.textViewDate.text = transaction.date.format(dateFormatter)

            when (transaction.type){
                CashTransactionType.DEPOSIT -> {
                    binding.textViewType.text = "存入"
                    binding.textViewType.setTextColor(ContextCompat.getColor(itemView.context, R.color.positive_green))
                    binding.textViewAmount.text = formatCurrency(transaction.amount, true)
                    binding.textViewAmount.setTextColor(ContextCompat.getColor(itemView.context, R.color.positive_green))
                }
                CashTransactionType.WITHDRAWAL -> {
                    binding.textViewType.text = "取出"
                    binding.textViewType.setTextColor(ContextCompat.getColor(itemView.context, R.color.negative_red))
                    binding.textViewAmount.text = formatCurrency(-transaction.amount, true)
                    binding.textViewAmount.setTextColor(ContextCompat.getColor(itemView.context, R.color.negative_red))
                }
                CashTransactionType.SELL -> {
                    binding.textViewType.text = "卖出"
                    binding.textViewType.setTextColor(ContextCompat.getColor(itemView.context, R.color.positive_green))
                    binding.textViewAmount.text = formatCurrency(transaction.amount, true)
                    binding.textViewAmount.setTextColor(ContextCompat.getColor(itemView.context, R.color.positive_green))
                }
                CashTransactionType.BUY -> {
                    binding.textViewType.text = "买入"
                    binding.textViewType.setTextColor(ContextCompat.getColor(itemView.context, R.color.negative_red))
                    binding.textViewAmount.text = formatCurrency(-transaction.amount, true)
                    binding.textViewAmount.setTextColor(ContextCompat.getColor(itemView.context, R.color.negative_red))
                }
                CashTransactionType.DIVIDEND -> {
                    binding.textViewType.text = "分红"
                    binding.textViewType.setTextColor(ContextCompat.getColor(itemView.context, R.color.positive_green))
                    binding.textViewAmount.text = formatCurrency(transaction.amount, true)
                    binding.textViewAmount.setTextColor(ContextCompat.getColor(itemView.context, R.color.positive_green))
                }
                CashTransactionType.SPLIT -> {

                }
            }
        }

        companion object {
            fun from(parent: ViewGroup): CashTransactionViewHolder {
                val layoutInflater = LayoutInflater.from(parent.context)
                val binding = ListItemCashTransactionBinding.inflate(layoutInflater, parent, false)
                return CashTransactionViewHolder(binding)
            }
        }
    }
}

class PortfolioDiffCallback : DiffUtil.ItemCallback<PortfolioListItem>() {
    override fun areItemsTheSame(oldItem: PortfolioListItem, newItem: PortfolioListItem): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: PortfolioListItem, newItem: PortfolioListItem): Boolean {
        return oldItem == newItem
    }
}