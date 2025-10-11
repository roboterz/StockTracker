package com.example.stocktracker.ui.screens

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.stocktracker.R
import com.example.stocktracker.data.StockHolding
import com.example.stocktracker.databinding.FragmentPortfolioBinding
import com.example.stocktracker.ui.StockAdapter
import com.example.stocktracker.ui.components.ChartSegment
import com.example.stocktracker.ui.components.formatCurrency
import com.example.stocktracker.ui.viewmodel.StockUiState
import com.example.stocktracker.ui.viewmodel.StockViewModel
import com.example.stocktracker.ui.viewmodel.StockViewModelFactory
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

class PortfolioFragment : Fragment() {

    private var _binding: FragmentPortfolioBinding? = null
    private val binding get() = _binding!!

    private val viewModel: StockViewModel by activityViewModels {
        StockViewModelFactory(requireActivity().application)
    }

    private val chartColors by lazy {
        listOf(
            R.color.chartColor1,
            R.color.chartColor2,
            R.color.chartColor3,
            R.color.chartColor4,
            R.color.chartColor5,
            R.color.chartColor6,
            R.color.chartColor7,
            R.color.chartColor8,
            R.color.chartColor9,
            R.color.chartColor10
        )
    }


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPortfolioBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val stockAdapter = StockAdapter { stock ->
            viewModel.selectStock(stock.id)
            findNavController().navigate(R.id.action_portfolioFragment_to_stockDetailFragment)
        }
        binding.recyclerViewStocks.adapter = stockAdapter

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { uiState ->
                    stockAdapter.submitList(uiState.holdings)
                    updateHeader(uiState)
                    updateChart(uiState.holdings)
                }
            }
        }

        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_add -> {
                    viewModel.prepareNewTransaction(null)
                    findNavController().navigate(R.id.action_portfolioFragment_to_addOrEditTransactionFragment)
                    true
                }
                else -> false
            }
        }
    }

    private fun updateChart(holdings: List<StockHolding>) {
        val totalMarketValue = holdings.sumOf { it.marketValue }
        if (totalMarketValue > 0) {
            val segments = holdings.mapIndexed { index, holding ->
                ChartSegment(
                    holding.marketValue.toFloat(),
                    chartColors[index % chartColors.size]
                )
            }
            binding.donutChart.setData(segments)
            updateLegend(holdings, totalMarketValue)
        }
    }

    private fun updateLegend(holdings: List<StockHolding>, totalMarketValue: Double) {
        binding.legendLayout.removeAllViews()
        holdings.forEachIndexed { index, holding ->
            val legendItem = createLegendItem(
                colorResId = chartColors[index % chartColors.size],
                name = holding.name,
                percentage = (holding.marketValue / totalMarketValue) * 100
            )
            binding.legendLayout.addView(legendItem)
        }
    }

    private fun createLegendItem(colorResId: Int, name: String, percentage: Double): View {
        val context = requireContext()
        // Create a horizontal LinearLayout for the item
        val itemLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = 8 } // Add some margin
            gravity = Gravity.CENTER_VERTICAL
        }

        // Color indicator
        val colorIndicator = View(context).apply {
            layoutParams = ViewGroup.LayoutParams(24, 24) // 10dp size
            background = ContextCompat.getDrawable(context, R.drawable.circle_background_legend)
            background.setTint(ContextCompat.getColor(context, colorResId))
        }

        // Stock Name TextView
        val nameTextView = TextView(context).apply {
            text = name
            setTextColor(ContextCompat.getColor(context, android.R.color.white))
            textSize = 14f // 14sp
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f // Weight
            ).also { it.marginStart = 16 } // 8dp margin
        }

        // Percentage TextView
        val percentageTextView = TextView(context).apply {
            text = String.format("%.2f%%", percentage)
            setTextColor(ContextCompat.getColor(context, android.R.color.white))
            textSize = 14f // 14sp
        }

        itemLayout.addView(colorIndicator)
        itemLayout.addView(nameTextView)
        itemLayout.addView(percentageTextView)

        return itemLayout
    }

    private fun updateHeader(uiState: StockUiState) {
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

        binding.header.metricDailyPl.metricLabel.text = "当日盈亏"
        binding.header.metricDailyPl.metricValue.text = formatCurrency(totalDailyPL, false)
        binding.header.metricDailyPl.metricPercent.text = String.format("%.2f%%", totalDailyPLPercent)
        updateMetricColor(binding.header.metricDailyPl.metricValue, binding.header.metricDailyPl.metricPercent, totalDailyPL)

        binding.header.metricHoldingPl.metricLabel.text = "持仓盈亏"
        binding.header.metricHoldingPl.metricValue.text = formatCurrency(totalHoldingPL, false)
        binding.header.metricHoldingPl.metricPercent.text = String.format("%.2f%%", totalHoldingPLPercent)
        updateMetricColor(binding.header.metricHoldingPl.metricValue, binding.header.metricHoldingPl.metricPercent, totalHoldingPL)

        binding.header.metricTotalPl.metricLabel.text = "总盈亏"
        binding.header.metricTotalPl.metricValue.text = formatCurrency(totalPL, true)
        binding.header.metricTotalPl.metricPercent.text = String.format("%s%.2f%%", if(totalPL >= 0) "+" else "", totalPLPercent.absoluteValue)
        updateMetricColor(binding.header.metricTotalPl.metricValue, binding.header.metricTotalPl.metricPercent, totalPL)

        binding.header.metricCash.metricLabel.text = "现金"
        binding.header.metricCash.metricValue.text = formatCurrency(cash, false)
        binding.header.metricCash.metricValue.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))
        binding.header.metricCash.metricPercent.visibility = View.GONE
    }

    private fun updateMetricColor(valueView: TextView, percentView: TextView, value: Double) {
        val color = if (value >= 0) {
            ContextCompat.getColor(requireContext(), R.color.positive_green)
        } else {
            ContextCompat.getColor(requireContext(), R.color.negative_red)
        }
        valueView.setTextColor(color)
        percentView.setTextColor(color)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

