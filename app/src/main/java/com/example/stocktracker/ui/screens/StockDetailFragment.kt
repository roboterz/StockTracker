package com.example.stocktracker.ui.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
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

    // *** 新增：CSV 文件选择器启动器 ***
    private lateinit var importCsvLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // *** 新增：初始化启动器 ***
        importCsvLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let {
                val stockId = viewModel.uiState.value.selectedStockId
                if (stockId != null) {
                    viewModel.importTransactionsFromCsv(it, stockId)
                }
            }
        }
    }

    override fun onCreateView(
// ... (onCreateView remains the same) ...
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStockDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
// ... (onViewCreated window insets handling remains the same) ...
        super.onViewCreated(view, savedInstanceState)

        // *** Key Fix: Handle window insets ***
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            binding.detailLayout.updatePadding(top = systemBars.top, bottom = systemBars.bottom)

            insets
        }

        // *** 新增：设置下拉刷新监听器 ***
        binding.swipeRefreshLayoutDetail.setOnRefreshListener {
            // 调用 ViewModel 的刷新方法
            // 这将刷新所有持仓，包括当前的，并更新 isRefreshing 状态
            viewModel.refreshData()
        }

        val transactionAdapter = TransactionAdapter { transaction ->
// ... (adapter setup remains the same) ...
            viewModel.prepareEditTransaction(transaction.id)
            findNavController().navigate(R.id.action_stockDetailFragment_to_addOrEditTransactionFragment)
        }
        binding.recyclerViewTransactions.adapter = transactionAdapter
        binding.recyclerViewTransactions.setHasFixedSize(true) // Optimization
        // *** 关键：禁用 RecyclerView 的嵌套滚动，因为我们在 XML 中设置了 ***
        // (已经在 XML 中通过 android:nestedScrollingEnabled="false" 完成)

        viewLifecycleOwner.lifecycleScope.launch {
// ... (uiState collection remains the same) ...
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { uiState ->
                    updateUi(uiState.selectedStock)
                    transactionAdapter.submitList(uiState.selectedStock.transactions.sortedByDescending { it.date })

                    // *** 新增：根据 ViewModel 状态更新刷新指示器 ***
                    binding.swipeRefreshLayoutDetail.isRefreshing = uiState.isRefreshing
                }
            }
        }

        setupToolbar()
    }

    private fun setupToolbar() {
// ... (setupToolbar remains the same) ...
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
                // *** 新增：处理 CSV 导入点击 ***
                R.id.action_import_csv -> {
                    // *** 关键修复：使用 "*/*" 允许选择所有文件 ***
                    // 之前的 "text/csv" 过滤器在某些设备上可能过于严格
                    importCsvLauncher.launch(arrayOf("*/*"))
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("SetTextI18n")
// ... (updateUi and updateMetricColor methods remain the same) ...
    private fun updateUi(stock: StockHolding) {
        binding.toolbar.title = stock.name
        // *** Key Fix: Set toolbar subtitle to ticker ***
        binding.toolbar.subtitle = stock.ticker
        binding.header.textViewMarketValue.text = formatCurrency(stock.marketValue, false)

        binding.header.metricDailyPl.metricLabel.text = "当日盈亏"
        binding.header.metricDailyPl.metricValue.text = formatCurrency(stock.dailyPL, true)
        binding.header.metricDailyPl.metricPercent.text = "${formatCurrency(stock.dailyPLPercent, true)}%"
        updateMetricColor(binding.header.metricDailyPl.metricValue, binding.header.metricDailyPl.metricPercent, stock.dailyPL)

        binding.header.metricHoldingPl.metricLabel.text = "持仓盈亏"
        binding.header.metricHoldingPl.metricValue.text = formatCurrency(stock.holdingPL, true)
        binding.header.metricHoldingPl.metricPercent.text = "${formatCurrency(stock.holdingPLPercent, true)}%"
        updateMetricColor(binding.header.metricHoldingPl.metricValue, binding.header.metricHoldingPl.metricPercent, stock.holdingPL)

        binding.header.metricTotalPl.metricLabel.text = "总盈亏"
        binding.header.metricTotalPl.metricValue.text = formatCurrency(stock.totalPL, true)
        binding.header.metricTotalPl.metricPercent.text = "${formatCurrency(stock.totalPLPercent, true)}%"
        updateMetricColor(binding.header.metricTotalPl.metricValue, binding.header.metricTotalPl.metricPercent, stock.totalPL)

        binding.header.metricDividend.metricLabel.text = "累计分红"
        binding.header.metricDividend.metricValue.text = formatCurrency(stock.cumulativeDividend, false)
        binding.header.metricDividend.metricValue.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))
        binding.header.metricDividend.metricPercent.visibility = View.GONE

        binding.header.layoutInfoCurrentPrice.infoLabel.text = "当前价格"
        binding.header.layoutInfoCurrentPrice.infoValue.text = stock.currentPrice.toString()

        binding.header.layoutInfoCostBasis.infoLabel.text = "成本价"
        binding.header.layoutInfoCostBasis.infoValue.text = DecimalFormat("#.####").format( stock.costBasis).toString()

        binding.header.layoutInfoQuantity.infoLabel.text = "数量"
        binding.header.layoutInfoQuantity.infoValue.text = DecimalFormat("#.##").format(stock.totalQuantity).toString()

        binding.header.layoutInfoTotalCost.infoLabel.text = "持仓总成本" // Update label
        // *** 修复：现在直接显示 totalCost，它代表剩余持仓的总成本（已含手续费）***
        binding.header.layoutInfoTotalCost.infoValue.text = formatCurrency(stock.totalCost, false)
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
// ... (onDestroyView remains the same) ...
        super.onDestroyView()
        _binding = null
    }
}