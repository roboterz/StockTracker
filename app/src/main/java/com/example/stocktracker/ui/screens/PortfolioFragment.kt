package com.example.stocktracker.ui.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.stocktracker.R
import com.example.stocktracker.databinding.FragmentPortfolioBinding
import com.example.stocktracker.ui.PortfolioAdapter
import com.example.stocktracker.ui.viewmodel.StockViewModel
import com.example.stocktracker.ui.viewmodel.StockViewModelFactory
import kotlinx.coroutines.launch

class PortfolioFragment : Fragment() {

    private var _binding: FragmentPortfolioBinding? = null
    private val binding get() = _binding!!

    private val viewModel: StockViewModel by activityViewModels {
        StockViewModelFactory(requireActivity().application)
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

        val portfolioAdapter = PortfolioAdapter { stock ->
            viewModel.selectStock(stock.id)
            findNavController().navigate(R.id.action_portfolioFragment_to_stockDetailFragment)
        }
        binding.recyclerViewStocks.adapter = portfolioAdapter

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { uiState ->
                    // *** 关键修复：过滤掉股数为0的持仓 ***
                    val activeHoldings = uiState.holdings.filter { it.totalQuantity != 0 }
                    val filteredUiState = uiState.copy(holdings = activeHoldings)


                    val portfolioItems = mutableListOf<PortfolioListItem>()
                    // 1. Add Header
                    portfolioItems.add(PortfolioListItem.Header(filteredUiState))
                    // 2. Add Profit/Loss Chart
                    portfolioItems.add(PortfolioListItem.ProfitLossChart())
                    // 3. Add Donut Chart if there are holdings
                    if (filteredUiState.holdings.isNotEmpty()) {
                        portfolioItems.add(PortfolioListItem.Chart(filteredUiState.holdings))
                    }
                    // 4. Add Stock items
                    filteredUiState.holdings.forEach { stock ->
                        portfolioItems.add(PortfolioListItem.Stock(stock))
                    }
                    portfolioAdapter.submitList(portfolioItems)
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

