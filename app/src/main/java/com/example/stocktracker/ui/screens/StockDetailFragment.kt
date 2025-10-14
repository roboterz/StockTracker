package com.example.stocktracker.ui.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.stocktracker.R
import com.example.stocktracker.data.StockHolding
import com.example.stocktracker.databinding.FragmentStockDetailBinding
import com.example.stocktracker.ui.TransactionAdapter
import com.example.stocktracker.ui.components.formatCurrency
import com.example.stocktracker.ui.viewmodel.StockViewModel
import com.example.stocktracker.ui.viewmodel.StockViewModelFactory
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import kotlin.math.absoluteValue

class StockDetailFragment : Fragment() {

    private var _binding: FragmentStockDetailBinding? = null
    private val binding get() = _binding!!

    private val viewModel: StockViewModel by activityViewModels {
        StockViewModelFactory(requireActivity().application)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStockDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // *** 关键修复：处理窗口边衬区 ***
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            binding.detailLayout.updatePadding(top = systemBars.top, bottom = systemBars.bottom)

            insets
        }

        val transactionAdapter = TransactionAdapter { transaction ->
            viewModel.prepareEditTransaction(transaction.id)
            findNavController().navigate(R.id.action_stockDetailFragment_to_addOrEditTransactionFragment)
        }
        binding.recyclerViewTransactions.adapter = transactionAdapter
        binding.recyclerViewTransactions.setHasFixedSize(true) // Optimization

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { uiState ->
                    updateUi(uiState.selectedStock)
                    transactionAdapter.submitList(uiState.selectedStock.transactions.sortedByDescending { it.date })
                }
            }
        }

        setupToolbar()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_add_transaction -> {
                    viewModel.prepareNewTransaction(viewModel.uiState.value.selectedStockId)
                    findNavController().navigate(R.id.action_stockDetailFragment_to_addOrEditTransactionFragment)
                    true
                }
                else -> false
            }
        }
    }

    private fun updateUi(stock: StockHolding) {
        binding.toolbar.title = stock.name
        // *** 关键修复：设置工具栏的副标题为股票代码 ***
        binding.toolbar.subtitle = stock.ticker
        binding.header.textViewMarketValue.text = formatCurrency(stock.marketValue, false)

        binding.header.metricDailyPl.metricLabel.text = "当日盈亏"
        binding.header.metricDailyPl.metricValue.text = formatCurrency(stock.dailyPL, true)
        binding.header.metricDailyPl.metricPercent.text = String.format("%s%.2f%%", if(stock.dailyPL >= 0) "+" else "", stock.dailyPLPercent.absoluteValue)
        updateMetricColor(binding.header.metricDailyPl.metricValue, binding.header.metricDailyPl.metricPercent, stock.dailyPL)

        binding.header.metricHoldingPl.metricLabel.text = "持仓盈亏"
        binding.header.metricHoldingPl.metricValue.text = formatCurrency(stock.holdingPL, true)
        binding.header.metricHoldingPl.metricPercent.text = String.format("%s%.2f%%", if(stock.holdingPL >= 0) "+" else "", stock.holdingPLPercent.absoluteValue)
        updateMetricColor(binding.header.metricHoldingPl.metricValue, binding.header.metricHoldingPl.metricPercent, stock.holdingPL)

        binding.header.metricTotalPl.metricLabel.text = "总盈亏"
        binding.header.metricTotalPl.metricValue.text = formatCurrency(stock.totalPL, true)
        binding.header.metricTotalPl.metricPercent.text = String.format("%s%.2f%%", if(stock.totalPL >= 0) "+" else "", stock.totalPLPercent.absoluteValue)
        updateMetricColor(binding.header.metricTotalPl.metricValue, binding.header.metricTotalPl.metricPercent, stock.totalPL)

        binding.header.metricDividend.metricLabel.text = "累计分红"
        binding.header.metricDividend.metricValue.text = formatCurrency(stock.cumulativeDividend, false)
        binding.header.metricDividend.metricValue.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))
        binding.header.metricDividend.metricPercent.visibility = View.GONE

        binding.header.layoutInfoCurrentPrice.infoLabel.text = "当前价格"
        binding.header.layoutInfoCurrentPrice.infoValue.text = stock.currentPrice.toString()

        binding.header.layoutInfoCostBasis.infoLabel.text = "成本价"
        binding.header.layoutInfoCostBasis.infoValue.text = formatCurrency(stock.costBasis, false)

        binding.header.layoutInfoQuantity.infoLabel.text = "数量"
        binding.header.layoutInfoQuantity.infoValue.text = stock.totalQuantity.toString()

        binding.header.layoutInfoTotalCost.infoLabel.text = "成本"
        binding.header.layoutInfoTotalCost.infoValue.text = formatCurrency(stock.totalCost - stock.totalSoldValue, false)
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

