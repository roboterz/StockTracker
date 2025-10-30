package com.example.stocktracker.ui.screens

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
import java.time.format.DateTimeFormatter
import java.util.*

class AddOrEditTransactionFragment : Fragment() {

    private var _binding: FragmentAddOrEditTransactionBinding? = null
    private val binding get() = _binding!!

    private val viewModel: StockViewModel by activityViewModels {
        StockViewModelFactory(requireActivity().application)
    }

    private var fetchedExchangeName: String? = null // *** 新增：用于存储获取到的交易所代码 ***

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
        val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")

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

        if (isEditMode && transaction != null) {
            binding.buttonToggleGroup.check(if (transaction.type == TransactionType.BUY) binding.buttonBuy.id else binding.buttonSell.id)
            binding.editTextPrice.setText(transaction.price.toString())
            binding.editTextQuantity.setText(transaction.quantity.toString())
            binding.editTextFee.setText(transaction.fee.toString())
            binding.editTextDate.setText(transaction.date.format(formatter))
        } else {
            binding.buttonToggleGroup.check(binding.buttonBuy.id)
            if (!isNewStockMode) {
                binding.editTextPrice.setText(stock.currentPrice.toString())
            }
            binding.editTextDate.setText(LocalDate.now().format(formatter))
        }
    }

    private fun setupListeners(transactionToEdit: Transaction?) {
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
            val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")

            val price = binding.editTextPrice.text.toString().toDoubleOrNull()
            val quantity = binding.editTextQuantity.text.toString().toDoubleOrNull()
            val fee = binding.editTextFee.text.toString().toDoubleOrNull() ?: 0.0
            val dateStr = binding.editTextDate.text.toString()

            val newStockId = if (isNewStockMode) binding.editTextStockId.text.toString() else ""
            val stockName = if (isNewStockMode) binding.editTextStockName.text.toString() else stock.name


            if (price == null || quantity == null || dateStr.isBlank() || (isNewStockMode && (newStockId.isBlank() || stockName.isBlank()))) {
                Toast.makeText(requireContext(), "请填写所有必填字段", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val transaction = Transaction(
                id = transactionToEdit?.id ?: UUID.randomUUID().toString(),
                date = LocalDate.parse(dateStr, formatter),
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
                viewModel.deleteTransaction(id)
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

