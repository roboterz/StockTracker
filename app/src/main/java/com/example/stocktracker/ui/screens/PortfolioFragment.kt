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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.stocktracker.R
import com.example.stocktracker.databinding.FragmentPortfolioBinding
import com.example.stocktracker.ui.PortfolioAdapter
import com.example.stocktracker.ui.viewmodel.StockViewModel
import com.example.stocktracker.ui.viewmodel.StockViewModelFactory
import kotlinx.coroutines.flow.collectLatest
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

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            binding.appBarLayout.updatePadding(top = systemBars.top)

            binding.recyclerViewStocks.updatePadding(bottom = systemBars.bottom)

            insets
        }


        val portfolioAdapter = PortfolioAdapter { stock ->
            viewModel.selectStock(stock.id)
            findNavController().navigate(R.id.action_portfolioFragment_to_stockDetailFragment)
        }
        binding.recyclerViewStocks.adapter = portfolioAdapter

        binding.swipeRefreshLayout.setOnRefreshListener {
            viewModel.refreshData()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { uiState ->
                        binding.swipeRefreshLayout.isRefreshing = uiState.isRefreshing

                        val activeHoldings = uiState.holdings.filter { it.totalQuantity > 0 }
                        val filteredUiState = uiState.copy(holdings = activeHoldings)


                        val portfolioItems = mutableListOf<PortfolioListItem>()
                        portfolioItems.add(PortfolioListItem.Header(filteredUiState))
                        portfolioItems.add(PortfolioListItem.ProfitLossChart())
                        if (filteredUiState.holdings.isNotEmpty()) {
                            portfolioItems.add(PortfolioListItem.Chart(filteredUiState.holdings))
                        }
                        filteredUiState.holdings.forEach { stock ->
                            portfolioItems.add(PortfolioListItem.Stock(stock))
                        }
                        portfolioAdapter.submitList(portfolioItems)
                    }
                }

                // 收集并响应Toast事件
                launch {
                    viewModel.toastEvents.collectLatest { message ->
                        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                    }
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

