package com.example.stocktracker.ui.screens

import android.os.Bundle
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
import com.example.stocktracker.data.CashTransaction
import com.example.stocktracker.data.CashTransactionType
import com.example.stocktracker.databinding.FragmentCashTransactionBinding
import com.example.stocktracker.ui.viewmodel.NavigationEvent
import com.example.stocktracker.ui.viewmodel.StockViewModel
import com.example.stocktracker.ui.viewmodel.StockViewModelFactory
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class CashTransactionFragment : Fragment() {

    private var _binding: FragmentCashTransactionBinding? = null
    private val binding get() = _binding!!

    private val viewModel: StockViewModel by activityViewModels {
        StockViewModelFactory(requireActivity().application)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCashTransactionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.cashTransactionLayout.updatePadding(top = systemBars.top, bottom = systemBars.bottom)
            insets
        }

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        // *** 检查是“添加”还是“编辑”模式 ***
        val transactionToEdit = viewModel.uiState.value.cashTransactionToEdit
        val isEditMode = transactionToEdit != null
        val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")

        // *** 更新UI ***
        binding.toolbar.title = if (isEditMode) "编辑现金交易" else "现金交易"
        binding.buttonDelete.isVisible = isEditMode

        if (isEditMode && transactionToEdit != null) {
            // 编辑模式：填充现有数据
            binding.editTextDate.setText(transactionToEdit.date.format(formatter))
            binding.editTextAmount.setText(transactionToEdit.amount.toString())
            val buttonId = if (transactionToEdit.type == CashTransactionType.DEPOSIT) binding.buttonDeposit.id else binding.buttonWithdrawal.id
            binding.buttonToggleGroup.check(buttonId)
        } else {
            // 添加模式：设置默认值
            binding.editTextDate.setText(LocalDate.now().format(formatter))
            binding.buttonToggleGroup.check(binding.buttonDeposit.id)
        }
        // *** 结束 ***


        binding.buttonSave.setOnClickListener {
            val amount = binding.editTextAmount.text.toString().toDoubleOrNull()
            val dateStr = binding.editTextDate.text.toString()
            val date = try {
                LocalDate.parse(dateStr, formatter)
            } catch (e: Exception) {
                null
            }

            if (amount == null || amount <= 0) {
                Toast.makeText(requireContext(), "请输入有效的金额", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (date == null) {
                Toast.makeText(requireContext(), "请输入有效的日期 (格式 yyyy/MM/dd)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val type = if (binding.buttonToggleGroup.checkedButtonId == binding.buttonDeposit.id) {
                CashTransactionType.DEPOSIT
            } else {
                CashTransactionType.WITHDRAWAL
            }

            // *** 修改：根据模式调用不同的 ViewModel 方法 ***
            if (isEditMode && transactionToEdit != null) {
                val updatedTransaction = transactionToEdit.copy(
                    date = date,
                    amount = amount,
                    type = type
                )
                viewModel.updateCashTransaction(updatedTransaction)
            } else {
                viewModel.addCashTransaction(amount, type, date)
            }
        }

        // *** 新增：删除按钮逻辑 ***
        binding.buttonDelete.setOnClickListener {
            if (isEditMode && transactionToEdit != null) {
                showDeleteConfirmationDialog(transactionToEdit.id)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.navigationEvents.collectLatest { event ->
                if (event is NavigationEvent.NavigateBack) {
                    findNavController().popBackStack()
                }
            }
        }
    }

    // *** 新增：删除确认对话框 ***
    private fun showDeleteConfirmationDialog(transactionId: String) {
        AlertDialog.Builder(requireContext(), R.style.AlertDialogCustom)
            .setTitle(R.string.dialog_delete_title)
            .setMessage(R.string.dialog_delete_cash_message) // 使用新的字符串
            .setPositiveButton(R.string.dialog_confirm_delete) { dialog, _ ->
                viewModel.deleteCashTransaction(transactionId)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_cancel) { dialog, _ ->
                dialog.cancel()
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
