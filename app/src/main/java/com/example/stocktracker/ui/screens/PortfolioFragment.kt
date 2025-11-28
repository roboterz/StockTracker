package com.example.stocktracker.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat.finishAffinity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.stocktracker.MainActivity
import com.example.stocktracker.R
import com.example.stocktracker.data.CashTransaction
import com.example.stocktracker.databinding.FragmentPortfolioBinding
import com.example.stocktracker.ui.PortfolioAdapter
import com.example.stocktracker.ui.viewmodel.StockUiState
import com.example.stocktracker.ui.viewmodel.StockViewModel
import com.example.stocktracker.ui.viewmodel.StockViewModelFactory
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter


class PortfolioFragment : Fragment() {

    private var _binding: FragmentPortfolioBinding? = null
    private val binding get() = _binding!!

    private val viewModel: StockViewModel by activityViewModels {
        StockViewModelFactory(requireActivity().application)
    }

    private lateinit var exportDbLauncher: ActivityResultLauncher<String>
    private lateinit var importDbLauncher: ActivityResultLauncher<Array<String>>

    private var selectedAssetType = AssetType.HOLDINGS
    private var portfolioAdapter: PortfolioAdapter? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupDbLaunchers()
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

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.appBarLayout.updatePadding(top = systemBars.top)
            binding.recyclerViewStocks.updatePadding(bottom = systemBars.bottom)
            insets
        }

        binding.swipeRefreshLayout.setOnRefreshListener {
            viewModel.refreshData()
            viewModel.updatePortfolioChart(viewModel.uiState.value.chartTimeRange)
        }

        portfolioAdapter = PortfolioAdapter(
            onStockClicked = { stock ->
                viewModel.selectStock(stock.id)
                findNavController().navigate(R.id.action_portfolioFragment_to_stockDetailFragment)
            },
            onHoldingsClicked = {
                selectedAssetType = AssetType.HOLDINGS
                updateAdapterList(viewModel.uiState.value)
            },
            onClosedClicked = {
                selectedAssetType = AssetType.CLOSED
                updateAdapterList(viewModel.uiState.value)
            },
            onCashClicked = {
                selectedAssetType = AssetType.CASH
                updateAdapterList(viewModel.uiState.value)
            },
            onCashItemClicked = { cashTransaction ->
                viewModel.prepareEditCashTransaction(cashTransaction.id)
                findNavController().navigate(R.id.action_portfolioFragment_to_cashTransactionFragment)
            },
            onTimeRangeSelected = { timeRange ->
                viewModel.updatePortfolioChart(timeRange)
            }
        )
        binding.recyclerViewStocks.adapter = portfolioAdapter

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { uiState ->
                    binding.toolbar.title = uiState.portfolioName
                    binding.swipeRefreshLayout.isRefreshing = uiState.isRefreshing
                    updateAdapterList(uiState)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.toastEvents.collectLatest { message ->
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            }
        }

        setupToolbarListeners()
    }

    private fun updateAdapterList(uiState: StockUiState) {
        val activeHoldings = uiState.holdings.filter { it.totalQuantity > 0 }
        val filteredUiState = uiState.copy(holdings = activeHoldings)

        val portfolioItems = mutableListOf<PortfolioListItem>()
        portfolioItems.add(PortfolioListItem.Header(filteredUiState))

        // *** 修改：传递基准数据 (benchmarkChartData) ***
        portfolioItems.add(PortfolioListItem.ProfitLossChart(
            chartData = uiState.portfolioChartData,
            benchmarkData = uiState.benchmarkChartData,
            selectedRange = uiState.chartTimeRange,
            isLoading = uiState.isChartLoading
        ))

        portfolioItems.add(PortfolioListItem.Chart(filteredUiState.holdings, selectedAssetType))


        when (selectedAssetType) {
            AssetType.HOLDINGS -> {
                portfolioItems.add(PortfolioListItem.StockHeader)
                activeHoldings.sortedByDescending { it.marketValue }.forEach { stock ->
                    portfolioItems.add(PortfolioListItem.Stock(stock))
                }
            }
            AssetType.CLOSED -> {
                portfolioItems.add(PortfolioListItem.ClosedPositionHeader)
                uiState.closedPositions.forEach { stock ->
                    portfolioItems.add(PortfolioListItem.ClosedPosition(stock))
                }
            }
            AssetType.CASH -> {
                portfolioItems.add(PortfolioListItem.CashHeader)
                uiState.cashTransactions.forEach { transaction ->
                    portfolioItems.add(PortfolioListItem.Cash(transaction))
                }
            }
        }

        portfolioAdapter?.submitList(portfolioItems)
    }


    private fun setupDbLaunchers() {
        exportDbLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
            uri?.let {
                viewModel.exportDatabase(it)
            }
        }

        importDbLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let {
                viewModel.importDatabase(it)
            }
            reStartApp()
        }
    }

    private fun setupToolbarListeners() {
        binding.toolbar.setOnClickListener {
            showEditPortfolioNameDialog(viewModel.uiState.value.portfolioName)
        }

        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_add -> {
                    viewModel.prepareNewTransaction(null)
                    findNavController().navigate(R.id.action_portfolioFragment_to_addOrEditTransactionFragment)
                    true
                }
                R.id.action_add_cash -> {
                    viewModel.prepareNewCashTransaction()
                    findNavController().navigate(R.id.action_portfolioFragment_to_cashTransactionFragment)
                    true
                }
                R.id.action_export_db -> {
                    val date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                    val defaultName = "stock_tracker_backup_$date.db"
                    exportDbLauncher.launch(defaultName)
                    true
                }
                R.id.action_import_db -> {
                    importDbLauncher.launch(arrayOf("application/octet-stream"))
                    true
                }
                else -> false
            }
        }
    }

    private fun showEditPortfolioNameDialog(currentName: String) {
        val editText = EditText(requireContext()).apply {
            setText(currentName)
            hint = "输入投资组合名称"
            setTextColor(requireContext().getColor(android.R.color.white))
            setBackgroundResource(android.R.color.transparent)
        }

        val padding = resources.getDimensionPixelSize(R.dimen.edit_text_padding)
        val container = android.widget.FrameLayout(requireContext()).apply {
            setPadding(padding, padding / 2, padding, padding / 2)
            addView(editText)
        }


        AlertDialog.Builder(requireContext(), R.style.AlertDialogCustom)
            .setTitle("编辑投资组合名称")
            .setView(container)
            .setPositiveButton("保存") { dialog, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotBlank()) {
                    viewModel.savePortfolioName(newName)
                } else {
                    Toast.makeText(requireContext(), "名称不能为空", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消") { dialog, _ ->
                dialog.cancel()
            }
            .show()
    }

    private fun reStartApp() {
        val intent = Intent(requireContext(), MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finishAffinity(requireActivity())

    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.recyclerViewStocks.adapter = null
        portfolioAdapter = null
        _binding = null
    }
}