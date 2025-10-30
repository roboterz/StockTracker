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

    // --- SAF 启动器 ---
    private lateinit var exportDbLauncher: ActivityResultLauncher<String>
    private lateinit var importDbLauncher: ActivityResultLauncher<Array<String>>
    // --- SAF 启动器结束 ---

    // *** 新增：跟踪当前选择的资产类型 ***
    private var selectedAssetType = AssetType.HOLDINGS
    private var portfolioAdapter: PortfolioAdapter? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 初始化 SAF 启动器
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
        }

        // *** 修改：初始化 Adapter 并传入所有点击回调 ***
        portfolioAdapter = PortfolioAdapter(
            onStockClicked = { stock ->
                viewModel.selectStock(stock.id)
                findNavController().navigate(R.id.action_portfolioFragment_to_stockDetailFragment)
            },
            onHoldingsClicked = {
                selectedAssetType = AssetType.HOLDINGS
                updateAdapterList(viewModel.uiState.value) // 使用当前状态立即重建列表
            },
            onClosedClicked = {
                selectedAssetType = AssetType.CLOSED
                updateAdapterList(viewModel.uiState.value)
            },
            onCashClicked = {
                selectedAssetType = AssetType.CASH
                updateAdapterList(viewModel.uiState.value)
            }
        )
        binding.recyclerViewStocks.adapter = portfolioAdapter

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { uiState ->
                    // *** 更新 Toolbar 标题 ***
                    binding.toolbar.title = uiState.portfolioName

                    binding.swipeRefreshLayout.isRefreshing = uiState.isRefreshing

                    // *** 修改：调用新的列表构建函数 ***
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

    // *** 新增：根据当前选择的
    private fun updateAdapterList(uiState: StockUiState) {
        val activeHoldings = uiState.holdings.filter { it.totalQuantity > 0 }
        // 创建一个仅包含活动持仓的 uiState 子集用于 Header 和 Chart
        val filteredUiState = uiState.copy(holdings = activeHoldings)

        val portfolioItems = mutableListOf<PortfolioListItem>()
        // 1. 添加 Header (始终显示活动持仓的统计)
        portfolioItems.add(PortfolioListItem.Header(filteredUiState))
        // 2. 添加盈亏图表
        portfolioItems.add(PortfolioListItem.ProfitLossChart())
        // 3. 添加资产分布图 (包含当前选中的按钮状态)
        // *** 修复：即使没有持仓，也要显示 Chart 项以显示按钮 ***
        portfolioItems.add(PortfolioListItem.Chart(filteredUiState.holdings, selectedAssetType))


        // 4. 根据所选选项卡添加相应的列表数据
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

        // 5. 提交列表到 Adapter
        portfolioAdapter?.submitList(portfolioItems)
    }


    // --- SAF 启动器初始化 ---
    private fun setupDbLaunchers() {
        // 导出：使用 ACTION_CREATE_DOCUMENT，指定默认文件名为 .db 格式
        exportDbLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
            uri?.let {
                viewModel.exportDatabase(it)
            }
        }

        // 导入：使用 ACTION_OPEN_DOCUMENT，只允许选择 .db 文件
        importDbLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let {
                viewModel.importDatabase(it)
            }
            reStartApp()
        }
    }
    // --- SAF 启动器结束 ---

    private fun setupToolbarListeners() {
        // *** 实现点击 Toolbar 标题编辑名称的逻辑 ***
        binding.toolbar.setOnClickListener {
            // 使用自定义对话框（由于不能使用 alert/confirm，这里使用 AlertDialog 模拟）
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
                    findNavController().navigate(R.id.action_portfolioFragment_to_cashTransactionFragment)
                    true
                }
                // --- 导出/导入菜单项处理 ---
                R.id.action_export_db -> {
                    val date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                    val defaultName = "stock_tracker_backup_$date.db"
                    // 启动文件创建选择器
                    exportDbLauncher.launch(defaultName)
                    true
                }
                R.id.action_import_db -> {
                    // 启动文件选择器，筛选 .db 文件
                    importDbLauncher.launch(arrayOf("application/octet-stream"))
                    true
                }
                // --- 导出/导入菜单项处理结束 ---
                else -> false
            }
        }
    }

    private fun showEditPortfolioNameDialog(currentName: String) {
        val editText = EditText(requireContext()).apply {
            setText(currentName)
            hint = "输入投资组合名称"
            setTextColor(requireContext().getColor(android.R.color.white)) // 设置文字颜色
            setBackgroundResource(android.R.color.transparent) // 移除背景，让它融入对话框
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
                    // *** 修复：直接调用 viewModel 的 savePortfolioName ***
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
        // restart Activity
        val intent = Intent(requireContext(), MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finishAffinity(requireActivity())// close all Activity

    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.recyclerViewStocks.adapter = null // *** 新增：清理 adapter 引用 ***
        portfolioAdapter = null // *** 新增：清理 adapter 引用 ***
        _binding = null
    }
}

