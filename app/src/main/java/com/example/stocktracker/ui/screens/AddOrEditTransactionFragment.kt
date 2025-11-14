package com.example.stocktracker.ui.screens

import android.app.DatePickerDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.stocktracker.R
import com.example.stocktracker.data.Transaction
import com.example.stocktracker.data.TransactionType
import com.example.stocktracker.databinding.FragmentAddOrEditTransactionBinding
import com.example.stocktracker.ui.viewmodel.NavigationEvent
import com.example.stocktracker.ui.viewmodel.StockViewModel
import com.example.stocktracker.ui.viewmodel.StockViewModelFactory
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

class AddOrEditTransactionFragment : Fragment() {

    private var _binding: FragmentAddOrEditTransactionBinding? = null
    private val binding get() = _binding!!

    private val viewModel: StockViewModel by activityViewModels {
        StockViewModelFactory(requireActivity().application)
    }

    private var fetchedExchangeName: String? = null // *** 新增：用于存储获取到的交易所代码 ***

    // *** 新增：用于存储选择的日期 ***
    private var selectedDate: LocalDate = LocalDate.now()
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddOrEditTransactionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            binding.addStockActivity.updatePadding(top = systemBars.top)
            binding.addStockActivity.updatePadding(bottom = systemBars.bottom)

            insets
        }


        val transactionToEdit = viewModel.uiState.value.transactionToEdit
        setupUI(transactionToEdit)
        setupListeners(transactionToEdit)
        observeNavigation()
    }

    private fun setupUI(transaction: Transaction?) {
        val stock = viewModel.uiState.value.selectedStock
        val isEditMode = transaction != null
        val isNewStockMode = stock.id.isEmpty() && !isEditMode
        // val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd") // 已移至类级别

        binding.toolbar.title = if (isEditMode) "编辑交易" else "添加交易"
        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        binding.layoutStockInfo.isVisible = !isNewStockMode
        binding.textInputLayoutStockId.isVisible = isNewStockMode
        binding.textInputLayoutStockName.isVisible = isNewStockMode

        binding.buttonDelete.isVisible = isEditMode

        if (!isNewStockMode) {
            binding.textViewStockName.text = stock.name
            binding.textViewCurrentPrice.text = "最新价: ${stock.currentPrice}"
        }

        if (isEditMode) {
            binding.buttonToggleGroup.check(if (transaction.type == TransactionType.BUY) binding.buttonBuy.id else binding.buttonSell.id)
            binding.editTextPrice.setText(transaction.price.toString())
            binding.editTextQuantity.setText(transaction.quantity.toInt().toString())
            binding.editTextFee.setText(transaction.fee.toString())
            // *** 修改：设置日期变量并更新文本 ***
            selectedDate = transaction.date
            binding.editTextDate.setText(selectedDate.format(dateFormatter))
        } else {
            binding.buttonToggleGroup.check(binding.buttonBuy.id)
            if (!isNewStockMode) {
                binding.editTextPrice.setText(stock.currentPrice.toString())
            }
            // *** 修改：设置日期变量并更新文本 ***
            selectedDate = LocalDate.now()
            binding.editTextDate.setText(selectedDate.format(dateFormatter))
        }
    }

    private fun setupListeners(transactionToEdit: Transaction?) {
        // *** 新增：为日期输入框添加点击监听器 ***
        binding.editTextDate.setOnClickListener {
            showDatePickerDialog()
        }

        // 自动获取股票名称和价格的逻辑
        binding.editTextStockId.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val ticker = binding.editTextStockId.text.toString().trim()
                if (ticker.isNotBlank()) {
                    lifecycleScope.launch {
                        binding.progressBarName.isVisible = true
                        binding.editTextStockName.isEnabled = false
                        try {
                            // *** 关键修复：调用新函数以同时获取名称和价格 ***
                            val data = viewModel.fetchInitialStockData(ticker)
                            if (data != null) {
                                binding.editTextStockName.setText(data.name)
                                binding.editTextPrice.setText(data.currentPrice.toString())
                                fetchedExchangeName = data.exchangeName // *** 存储交易所代码 ***
                            } else {
                                fetchedExchangeName = null // *** 失败时清除 ***
                                Toast.makeText(requireContext(), "无法找到股票数据", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            fetchedExchangeName = null // *** 异常时清除 ***
                            Log.e("AddOrEditTransaction", "Failed to fetch initial stock data for $ticker", e)
                            Toast.makeText(requireContext(), "获取股票数据失败", Toast.LENGTH_SHORT).show()
                        } finally {
                            binding.progressBarName.isVisible = false
                            binding.editTextStockName.isEnabled = true
                        }
                    }
                }
            }
        }

        binding.buttonSave.setOnClickListener {
            val stock = viewModel.uiState.value.selectedStock
            val isNewStockMode = stock.id.isEmpty() && transactionToEdit == null
            // val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd") // 已移至类级别

            val price = binding.editTextPrice.text.toString().toDoubleOrNull()
            val quantity = binding.editTextQuantity.text.toString().toDoubleOrNull()
            val fee = binding.editTextFee.text.toString().toDoubleOrNull() ?: 0.0
            // *** 修改：从 selectedDate 获取日期 ***
            val dateStr = selectedDate.format(dateFormatter) // 确保 dateStr 非空

            val newStockId = if (isNewStockMode) binding.editTextStockId.text.toString() else ""
            val stockName = if (isNewStockMode) binding.editTextStockName.text.toString() else stock.name


            if (price == null || quantity == null || dateStr.isBlank() || (isNewStockMode && (newStockId.isBlank() || stockName.isBlank()))) {
                Toast.makeText(requireContext(), "请填写所有必填字段", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val transaction = Transaction(
                id = transactionToEdit?.id ?: UUID.randomUUID().toString(),
                // *** 修改：使用 selectedDate 变量 ***
                date = selectedDate,
                type = if (binding.buttonToggleGroup.checkedButtonId == binding.buttonBuy.id) TransactionType.BUY else TransactionType.SELL,
                quantity = quantity,
                price = price,
                fee = fee
            )

            viewModel.saveOrUpdateTransaction(
                transaction,
                stock.id.ifEmpty { null },
                newStockId,
                stockName,
                fetchedExchangeName // *** 传递交易所代码 ***
            )
        }

        binding.buttonDelete.setOnClickListener {
            transactionToEdit?.id?.let { id ->
                // *** 修改：弹出确认对话框后再删除 ***
                showDeleteConfirmationDialog(id)
            }
        }

        binding.buttonToggleGroup.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (!isChecked) {
                if (group.checkedButtonId == View.NO_ID) {
                    group.check(checkedId)
                }
            }
        }
    }

    // *** 新增：显示日期选择器的方法 ***
    private fun showDatePickerDialog() {
        val calendar = Calendar.getInstance()
        // 使用当前选择的日期作为选择器中的默认日期
        calendar.time = Date.from(selectedDate.atStartOfDay(ZoneId.systemDefault()).toInstant())

        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val day = calendar.get(Calendar.DAY_OF_MONTH)

        // 使用自定义的暗色主题
        val datePickerDialog = DatePickerDialog(
            requireContext(),
            R.style.AlertDialogCustom, // 应用自定义暗色主题
            { _, selectedYear, selectedMonth, selectedDayOfMonth ->
                // 月份是从0开始的，所以+1
                selectedDate = LocalDate.of(selectedYear, selectedMonth + 1, selectedDayOfMonth)
                // 更新 EditText 显示新的日期
                binding.editTextDate.setText(selectedDate.format(dateFormatter))
            },
            year,
            month,
            day
        )
        datePickerDialog.show()
    }


    // *** 新增：删除确认对话框方法 ***
    private fun showDeleteConfirmationDialog(transactionId: String) {
        // 使用自定义 AlertDialog 样式
        AlertDialog.Builder(requireContext(), R.style.AlertDialogCustom)
            .setTitle(R.string.dialog_delete_title)
            .setMessage(R.string.dialog_delete_message)
            .setPositiveButton(R.string.dialog_confirm_delete) { dialog, _ ->
                // 确认后执行删除操作
                viewModel.deleteTransaction(transactionId)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_cancel) { dialog, _ ->
                dialog.cancel()
            }
            .show()
    }
    // *** 新增结束 ***


    private fun observeNavigation() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.navigationEvents.collectLatest { event ->
                if (event is NavigationEvent.NavigateBack) {
                    findNavController().popBackStack()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}