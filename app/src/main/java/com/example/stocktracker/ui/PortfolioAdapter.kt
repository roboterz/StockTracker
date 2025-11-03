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
import java.text.DecimalFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// ... (View Holder Types constants remain the same) ...
private const val ITEM_VIEW_TYPE_HEADER = 0
private const val ITEM_VIEW_TYPE_PROFIT_LOSS_CHART = 1
private const val ITEM_VIEW_TYPE_CHART = 2
// *** 修改：重新定义视图类型 ***
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
    private val onCashItemClicked: (CashTransaction) -> Unit // *** 新增：现金条目点击回调 ***
) : ListAdapter<PortfolioListItem, RecyclerView.ViewHolder>(PortfolioDiffCallback()) {

    override fun getItemViewType(position: Int): Int {
// ... (getItemViewType logic remains the same) ...
        return when (getItem(position)) {
            is PortfolioListItem.Header -> ITEM_VIEW_TYPE_HEADER
            is PortfolioListItem.ProfitLossChart -> ITEM_VIEW_TYPE_PROFIT_LOSS_CHART
            is PortfolioListItem.Chart -> ITEM_VIEW_TYPE_CHART
            // *** 新增类型 ***
            is PortfolioListItem.StockHeader -> ITEM_VIEW_TYPE_STOCK_HEADER
            is PortfolioListItem.Stock -> ITEM_VIEW_TYPE_STOCK
            is PortfolioListItem.ClosedPositionHeader -> ITEM_VIEW_TYPE_CLOSED_HEADER
            is PortfolioListItem.ClosedPosition -> ITEM_VIEW_TYPE_CLOSED_POSITION
            is PortfolioListItem.CashHeader -> ITEM_VIEW_TYPE_CASH_HEADER
            is PortfolioListItem.Cash -> ITEM_VIEW_TYPE_CASH_TRANSACTION
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
// ... (onCreateViewHolder logic remains the same, except for cash transaction) ...
        return when (viewType) {
            ITEM_VIEW_TYPE_HEADER -> HeaderViewHolder.from(parent)
            ITEM_VIEW_TYPE_PROFIT_LOSS_CHART -> ProfitLossChartViewHolder.from(parent)
            ITEM_VIEW_TYPE_CHART -> ChartViewHolder.from(parent, onHoldingsClicked, onClosedClicked, onCashClicked)
            // *** 新增 ViewHolder 创建 ***
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
// ... (onBindViewHolder logic remains the same, except for cash transaction) ...
        when (holder) {
            is HeaderViewHolder -> {
                val headerItem = getItem(position) as PortfolioListItem.Header
                holder.bind(headerItem.uiState)
            }
            is ProfitLossChartViewHolder -> {
                holder.bind()
            }
            is ChartViewHolder -> {
                val chartItem = getItem(position) as PortfolioListItem.Chart
                holder.bind(chartItem.holdings, chartItem.selectedType)
            }
            is StockViewHolder -> {
                val stockItem = getItem(position) as PortfolioListItem.Stock
                holder.bind(stockItem.stock, onStockClicked)
            }
            // *** 新增 ViewHolder 绑定 ***
            is StockHeaderViewHolder -> holder.bind()
            is ClosedPositionHeaderViewHolder -> holder.bind()
            is ClosedPositionViewHolder -> {
                val closedItem = getItem(position) as PortfolioListItem.ClosedPosition
                holder.bind(closedItem.stock, onStockClicked)
            }
            is CashHeaderViewHolder -> holder.bind()
            is CashTransactionViewHolder -> {
                val cashItem = getItem(position) as PortfolioListItem.Cash
                // *** 修改：传入点击回调 ***
                holder.bind(cashItem.transaction, onCashItemClicked)
            }
        }
    }

    // --- ViewHolders ---

    // ... (HeaderViewHolder, ProfitLossChartViewHolder, ChartViewHolder, StockHeaderViewHolder, StockViewHolder, ClosedPositionHeaderViewHolder, ClosedPositionViewHolder, CashHeaderViewHolder remain the same) ...
    class HeaderViewHolder(private val binding: ListItemPortfolioHeaderBinding) : RecyclerView.ViewHolder(binding.root) {

        @SuppressLint("SetTextI18n")
        fun bind(uiState: StockUiState) {
            val holdings = uiState.holdings
            val totalMarketValue = holdings.sumOf { it.marketValue }
            val totalDailyPL = holdings.sumOf { it.dailyPL }
            val totalHoldingPL = holdings.sumOf { it.holdingPL }
            val totalPL = holdings.sumOf { it.totalPL }
            val cash = uiState.cashBalance

            // Re-calculate the cost basis for percentage calculation for the whole portfolio
            // Total cost is the sum of totalCost of all holdings.
            val totalCost = holdings.sumOf { it.totalCost }

            // Using totalMarketValue + (totalCost - totalSoldValue) is wrong for percentage base.
            // totalCost is now the total cost of remaining shares.
            val totalDailyPLPercent = if (totalMarketValue - totalDailyPL != 0.0) (totalDailyPL / (totalMarketValue - totalDailyPL)) * 100 else 0.0
            val totalHoldingPLPercent = if (totalCost > 0) (totalHoldingPL / totalCost) * 100 else 0.0 // Corrected calculation base
            val totalPLPercent = if (totalCost > 0) (totalPL / totalCost) * 100 else 0.0 // Corrected calculation base

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

    class ProfitLossChartViewHolder(private val binding: ListItemPortfolioPlChartBinding) : RecyclerView.ViewHolder(binding.root) {

        private val sampleData: Map<Int, Pair<List<List<Float>>, List<String>>>
        private var listenersAreSet = false
        private val timeRangeButtons: List<Button>
        private var currentTimeRangeId: Int = R.id.button_1m

        init {
            timeRangeButtons = listOf(
                binding.button5d, binding.button1m, binding.button3m, binding.button6m,
                binding.button1y, binding.button5y, binding.buttonAll
            )

            val today = LocalDate.now()
            val monthFmt = DateTimeFormatter.ofPattern("MM-dd")
            val yearFmt = DateTimeFormatter.ofPattern("yyyy-MM")
            val yearsFmt = DateTimeFormatter.ofPattern("yyyy")

            sampleData = mapOf(
                R.id.button_1m to (
                        listOf(
                            listOf(10f, 15f, 13f, 20f, 18f, 25f, 22f, 19f, 28f, 30f, 26f, 32f, 23f, 25f, 31f, 22f, 18f, 19f, 20f, 23f, 27f, 31f, 35f, 40f, 41f, 42f, 44f, 46f, 35f, 22f),
                            listOf(12f, 14f, 11f, 18f, 16f, 23f, 20f, 17f, 26f)
                        ) to
                                listOf(today.minusMonths(1).format(monthFmt), "", today.minusDays(15).format(monthFmt), "", today.format(monthFmt))
                        ),
                R.id.button_5d to (
                        listOf(
                            listOf(10f, 12f, 8f, 15f, 14f),
                            listOf(11f, 11.5f, 9f)
                        ) to
                                listOf(today.minusDays(4).format(monthFmt), "", today.minusDays(2).format(monthFmt), "", today.format(monthFmt))
                        ),
                R.id.button_3m to (
                        listOf(
                            listOf(5f, 8f, 12f, 10f, 18f, 15f, 22f, 25f, 20f, 28f, 30f, 26f),
                            listOf(7f, 9f, 13f, 11f, 19f, 16f)
                        ) to
                                listOf(today.minusMonths(3).format(monthFmt), "", today.minusMonths(1).minusDays(15).format(monthFmt), "", today.format(monthFmt))
                        ),
                R.id.button_6m to (
                        listOf(
                            listOf(20f, 18f, 15f, 10f, 12f, 18f, 5f, 15f, 25f, 30f, 28f, 26f),
                            listOf(18f, 16f, 13f, 8f, 10f, 16f)
                        ) to
                                listOf(today.minusMonths(6).format(yearFmt), "", today.minusMonths(3).format(yearFmt), "", today.format(yearFmt))
                        ),
                R.id.button_1y to (
                        listOf(
                            listOf(30f, 35f, 32f, 28f, 25f, 20f, 18f, 15f, 22f, 28f, 30f, 26f),
                            listOf(28f, 33f, 30f, 26f, 23f, 18f)
                        ) to
                                listOf(today.minusYears(1).format(yearFmt), "", today.minusMonths(6).format(yearFmt), "", today.format(yearFmt))
                        ),
                R.id.button_5y to (
                        listOf(
                            listOf(10f, 20f, 15f, 30f, 25f, 40f, 50f, 45f, 35f, 25f, 30f, 26f),
                            listOf(12f, 22f, 17f, 32f, 27f, 42f)
                        ) to
                                listOf(today.minusYears(5).format(yearsFmt), "", today.minusYears(2).minusMonths(6).format(yearsFmt), "", today.format(yearsFmt))
                        ),
                R.id.button_all to (
                        listOf(
                            listOf(5f, 10f, 20f, 15f, 30f, 25f, 40f, 50f, 45f, 35f, 25f, 30f, 26f),
                            listOf(7f, 12f, 22f, 17f, 32f, 27f)
                        ) to
                                listOf("开始", "", "", "", "现在")
                        )
            )
        }

        fun bind() {
            if (!listenersAreSet) {
                timeRangeButtons.forEach { button ->
                    button.setOnClickListener { handleTimeRangeClick(it as Button) }
                }
                listenersAreSet = true
            }
            updateTimeRangeButtonTints()
            updateChart(currentTimeRangeId)
        }

        private fun handleTimeRangeClick(clickedButton: Button) {
            currentTimeRangeId = clickedButton.id
            updateTimeRangeButtonTints()
            updateChart(currentTimeRangeId)
        }

        private fun updateTimeRangeButtonTints() {
            val selectedColor = ColorStateList.valueOf(Color.parseColor("#2689FE"))
            val defaultColor = ColorStateList.valueOf(Color.TRANSPARENT)

            timeRangeButtons.forEach { button ->
                button.backgroundTintList = if (button.id == currentTimeRangeId) selectedColor else defaultColor
            }
        }

        private fun updateChart(checkedId: Int) {
            val (dataSets, dates) = sampleData[checkedId] ?: return

            val mainLineColor = ContextCompat.getColor(itemView.context, R.color.chartLineBlue)
            val djiLineColor = ContextCompat.getColor(itemView.context, R.color.chartLineOrange)

            val lineDataList = mutableListOf<LineData>()
            if (dataSets.isNotEmpty()) {
                lineDataList.add(LineData(dataSets[0], mainLineColor))
            }
            if (dataSets.size > 1) {
                lineDataList.add(LineData(dataSets[1], djiLineColor))
            }

            binding.plLineChart.setData(lineDataList)

            val allPoints = dataSets.flatten()
            if (allPoints.isNotEmpty()) {
                val maxVal = allPoints.maxOrNull()!!
                val minVal = allPoints.minOrNull()!!
                val midVal = (maxVal + minVal) / 2
                binding.plChartPercentMax.text = String.format("%+.2f%%", maxVal)
                binding.plChartPercentMid.text = String.format("%+.2f%%", midVal)
                binding.plChartPercentMin.text = String.format("%+.2f%%", minVal)
            }

            val dateLabels = listOf(binding.dateStart, binding.dateMidLeft, binding.dateMid, binding.dateMidRight, binding.dateEnd)
            dateLabels.forEachIndexed { index, textView ->
                if (index < dates.size && dates[index].isNotBlank()) {
                    textView.text = dates[index]
                    textView.visibility = View.VISIBLE
                } else {
                    textView.visibility = View.INVISIBLE
                }
            }
            if (dates.size > 2 && dates[2].isNotBlank()) binding.dateMid.visibility = View.VISIBLE
        }

        companion object {
            fun from(parent: ViewGroup): ProfitLossChartViewHolder {
                val layoutInflater = LayoutInflater.from(parent.context)
                val binding = ListItemPortfolioPlChartBinding.inflate(layoutInflater, parent, false)
                return ProfitLossChartViewHolder(binding)
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
                // *** 设置监听器以调用 Fragment 中的回调 ***
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

            // *** 根据传入的状态更新按钮的选中样式 ***
            binding.buttonHoldingsDetail.isSelected = selectedType == AssetType.HOLDINGS
            binding.buttonClosedPositionsDetail.isSelected = selectedType == AssetType.CLOSED
            binding.buttonCashDetail.isSelected = selectedType == AssetType.CASH

            val totalMarketValue = holdings.sumOf { it.marketValue }
            if (totalMarketValue <= 0) {
                binding.donutChart.setData(emptyList())
                binding.legendLayout.removeAllViews()
                return
            }

            // *** Key Fix: Sort first, then group ***
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

    // --- Stock (Active Holding) ViewHolder ---
    class StockHeaderViewHolder(binding: ListItemStockHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind() { /* 静态布局, 无需绑定 */ }

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
            // *** 修复：现在直接显示 totalCost，它代表剩余持仓的总成本（已含手续费）***
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

    // --- 新增：Closed Position ViewHolders ---
    class ClosedPositionHeaderViewHolder(binding: ListItemClosedPositionHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind() { /* 静态布局, 无需绑定 */ }

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

    // --- 新增：Cash Transaction ViewHolders ---
    class CashHeaderViewHolder(binding: ListItemCashHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind() { /* 静态布局, 无需绑定 */ }

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

        @SuppressLint("SetTextI18n")
        fun bind(transaction: com.example.stocktracker.data.CashTransaction, onCashItemClicked: (CashTransaction) -> Unit) {
            // *** 新增：设置点击监听器 ***
            itemView.setOnClickListener {
                // 只有非股票关联的现金交易（存入/取出）才可以编辑
                if (transaction.stockTransactionId == null) {
                    onCashItemClicked(transaction)
                }
            }

            binding.textViewDate.text = transaction.date.format(dateFormatter)
            //val isDeposit = transaction.type == com.example.stocktracker.data.CashTransactionType.DEPOSIT

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


//            if (isDeposit) {
//                binding.textViewType.text = "存入"
//                binding.textViewType.setTextColor(ContextCompat.getColor(itemView.context, R.color.positive_green))
//                binding.textViewAmount.text = formatCurrency(transaction.amount, true)
//                binding.textViewAmount.setTextColor(ContextCompat.getColor(itemView.context, R.color.positive_green))
//            } else {
//                binding.textViewType.text = "取出"
//                binding.textViewType.setTextColor(ContextCompat.getColor(itemView.context, R.color.negative_red))
//                binding.textViewAmount.text = formatCurrency(-transaction.amount, true)
//                binding.textViewAmount.setTextColor(ContextCompat.getColor(itemView.context, R.color.negative_red))
//            }
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

// --- DiffUtil Callback ---
// ... (PortfolioDiffCallback remains the same) ...

class PortfolioDiffCallback : DiffUtil.ItemCallback<PortfolioListItem>() {
    override fun areItemsTheSame(oldItem: PortfolioListItem, newItem: PortfolioListItem): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: PortfolioListItem, newItem: PortfolioListItem): Boolean {
        return oldItem == newItem
    }
}
