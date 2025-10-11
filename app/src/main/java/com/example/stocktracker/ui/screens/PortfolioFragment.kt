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
                    // 过滤掉持仓数量为0的股票
                    val holdingsWithShares = uiState.holdings.filter { it.totalQuantity != 0 }

                    val portfolioItems = mutableListOf<PortfolioListItem>()
                    // 1. 添加头部 (仍然使用完整的uiState来进行总体计算)
                    portfolioItems.add(PortfolioListItem.Header(uiState))
                    // 2. 如果有持仓，则添加图表
                    if (holdingsWithShares.isNotEmpty()) {
                        portfolioItems.add(PortfolioListItem.Chart(holdingsWithShares))
                    }
                    // 3. 添加股票列表项
                    holdingsWithShares.forEach { stock ->
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

