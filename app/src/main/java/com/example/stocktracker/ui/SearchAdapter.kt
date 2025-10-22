package com.example.stocktracker.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.stocktracker.databinding.ListItemSearchResultBinding
import com.example.stocktracker.network.SymbolSearchResult

/**
 * 用于在添加交易页面显示股票搜索结果的RecyclerView适配器。
 */
class SearchAdapter(private val onItemClicked: (SymbolSearchResult) -> Unit) :
    ListAdapter<SymbolSearchResult, SearchAdapter.SearchViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchViewHolder {
        val binding = ListItemSearchResultBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SearchViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SearchViewHolder, position: Int) {
        val current = getItem(position)
        holder.itemView.setOnClickListener {
            onItemClicked(current)
        }
        holder.bind(current)
    }

    class SearchViewHolder(private val binding: ListItemSearchResultBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(result: SymbolSearchResult) {
            binding.textViewSymbol.text = result.symbol
            binding.textViewName.text = result.name
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<SymbolSearchResult>() {
            override fun areItemsTheSame(oldItem: SymbolSearchResult, newItem: SymbolSearchResult): Boolean {
                return oldItem.symbol == newItem.symbol
            }

            override fun areContentsTheSame(oldItem: SymbolSearchResult, newItem: SymbolSearchResult): Boolean {
                return oldItem == newItem
            }
        }
    }
}
