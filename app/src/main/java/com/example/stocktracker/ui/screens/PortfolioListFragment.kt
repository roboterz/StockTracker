package com.example.stocktracker.ui.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.stocktracker.R
import com.example.stocktracker.databinding.FragmentPortfolioListBinding
import com.example.stocktracker.ui.PortfolioListAdapter
import com.example.stocktracker.ui.viewmodel.PortfolioListViewModel
import kotlinx.coroutines.launch

class PortfolioListFragment : Fragment() {

    private var _binding: FragmentPortfolioListBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PortfolioListViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPortfolioListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = PortfolioListAdapter { summary ->
            val bundle = Bundle().apply {
                putString("portfolioId", summary.portfolio.id)
                putString("portfolioName", summary.portfolio.name)
            }
            findNavController().navigate(R.id.action_portfolioListFragment_to_portfolio_graph, bundle)
        }
        binding.recyclerViewPortfolios.adapter = adapter

        binding.swipeRefreshLayout.setOnRefreshListener {
            viewModel.refreshAll()
        }

        binding.fabAddPortfolio.setOnClickListener {
            showAddPortfolioDialog()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.summaries.collect { summaries ->
                        adapter.submitList(summaries)
                    }
                }
                launch {
                    viewModel.isRefreshing.collect { isRefreshing ->
                        binding.swipeRefreshLayout.isRefreshing = isRefreshing
                    }
                }
            }
        }
    }

    private fun showAddPortfolioDialog() {
        val editText = EditText(requireContext()).apply {
            hint = "输入投资组合名称"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("新建投资组合")
            .setView(editText)
            .setPositiveButton("创建") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotBlank()) {
                    viewModel.addPortfolio(name)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
