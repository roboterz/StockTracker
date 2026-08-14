package com.example.stocktracker.ui.screens

import android.graphics.Canvas
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
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.stocktracker.R
import com.example.stocktracker.data.PortfolioSummary
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

        val adapter = PortfolioListAdapter(
            onPortfolioClick = { summary ->
                val bundle = Bundle().apply {
                    putString("portfolioId", summary.portfolio.id)
                    putString("portfolioName", summary.portfolio.name)
                }
                findNavController().navigate(R.id.action_portfolioListFragment_to_portfolio_graph, bundle)
            },
            onDeleteClick = { summary ->
                showDeleteConfirmationDialog(summary)
            }
        )
        binding.recyclerViewPortfolios.adapter = adapter

        setupSwipeToReveal(adapter)

        binding.recyclerViewPortfolios.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    if (adapter.getOpenedPosition() != RecyclerView.NO_POSITION) {
                        adapter.setOpenedPosition(RecyclerView.NO_POSITION)
                    }
                }
            }
        })

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

    private fun showDeleteConfirmationDialog(summary: PortfolioSummary) {
        AlertDialog.Builder(requireContext())
            .setTitle("确认删除")
            .setMessage("您确定要删除投资组合 \"${summary.portfolio.name}\" 吗？此操作不可恢复。提醒：建议您在删除前备份相关数据。")
            .setPositiveButton("确认删除") { _, _ ->
                val adapter = binding.recyclerViewPortfolios.adapter as? PortfolioListAdapter
                adapter?.setOpenedPosition(RecyclerView.NO_POSITION)
                viewModel.deletePortfolio(summary.portfolio)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setupSwipeToReveal(adapter: PortfolioListAdapter) {
        val swipeHandler = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            private var lastDx = 0f

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 1.1f
            override fun getSwipeEscapeVelocity(defaultValue: Float): Float = defaultValue * 10

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    lastDx = 0f
                    if (viewHolder != null) {
                        val position = viewHolder.bindingAdapterPosition
                        if (adapter.getOpenedPosition() != position && adapter.getOpenedPosition() != RecyclerView.NO_POSITION) {
                            adapter.setOpenedPosition(RecyclerView.NO_POSITION)
                        }
                    }
                }
                super.onSelectedChanged(viewHolder, actionState)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    val vh = viewHolder as PortfolioListAdapter.PortfolioViewHolder
                    val foregroundView = vh.binding.viewForeground
                    val deleteButtonCard = vh.binding.buttonDeleteCard
                    val buttonWidth = resources.getDimensionPixelSize(R.dimen.delete_button_width).toFloat()
                    val revealDistance = buttonWidth + 12f

                    val isOpened = vh.bindingAdapterPosition == adapter.getOpenedPosition()
                    
                    if (isCurrentlyActive) {
                        lastDx = dX
                    }

                    var translationX = dX
                    if (isOpened) {
                        translationX = dX - revealDistance
                    }
                    
                    // 动画阶段：如果用户已经滑过阈值，修正动画目标，防止回弹闪烁
                    if (!isCurrentlyActive && lastDx != 0f) {
                        if (isOpened && lastDx > revealDistance / 4) {
                            translationX = dX // 向 0 回弹
                        } else if (!isOpened && lastDx < -revealDistance / 4) {
                            translationX = dX - revealDistance // 向开启位置回弹
                        }
                    }
                    
                    val finalTranslation = Math.max(-revealDistance, Math.min(0f, translationX))

                    getDefaultUIUtil().onDraw(
                        c, recyclerView, foregroundView, finalTranslation, dY, actionState, isCurrentlyActive
                    )
                    
                    deleteButtonCard.translationX = revealDistance + finalTranslation
                    val progress = Math.abs(finalTranslation) / revealDistance
                    deleteButtonCard.alpha = Math.min(1f, progress * 1.5f)
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                val vh = viewHolder as PortfolioListAdapter.PortfolioViewHolder
                val position = vh.bindingAdapterPosition
                
                if (position != RecyclerView.NO_POSITION) {
                    val buttonWidth = resources.getDimensionPixelSize(R.dimen.delete_button_width).toFloat()
                    val revealDistance = buttonWidth + 12f
                    val isOpened = position == adapter.getOpenedPosition()
                    
                    if (!isOpened && lastDx < -revealDistance / 4) {
                        adapter.setOpenedPosition(position)
                    } else if (isOpened && lastDx > revealDistance / 4) {
                        adapter.setOpenedPosition(RecyclerView.NO_POSITION)
                    } else {
                        vh.updateRevealState(isOpened)
                    }
                }

                lastDx = 0f
                getDefaultUIUtil().clearView(vh.binding.viewForeground)
            }
        }
        val itemTouchHelper = ItemTouchHelper(swipeHandler)
        itemTouchHelper.attachToRecyclerView(binding.recyclerViewPortfolios)
    }

}
