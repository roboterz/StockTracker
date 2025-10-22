package com.example.stocktracker.ui.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.stocktracker.data.CashTransactionType
import com.example.stocktracker.databinding.FragmentCashTransactionBinding
import com.example.stocktracker.ui.viewmodel.NavigationEvent
import com.example.stocktracker.ui.viewmodel.StockViewModel
import com.example.stocktracker.ui.viewmodel.StockViewModelFactory
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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

        binding.buttonSave.setOnClickListener {
            val amount = binding.editTextAmount.text.toString().toDoubleOrNull()
            if (amount == null || amount <= 0) {
                Toast.makeText(requireContext(), "请输入有效的金额", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val type = if (binding.buttonToggleGroup.checkedButtonId == binding.buttonDeposit.id) {
                CashTransactionType.DEPOSIT
            } else {
                CashTransactionType.WITHDRAWAL
            }
            viewModel.addCashTransaction(amount, type)
        }

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
