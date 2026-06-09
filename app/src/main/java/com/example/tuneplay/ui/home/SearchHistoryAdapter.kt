package com.example.tuneplay.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.tuneplay.data.local.entity.SearchHistory
import com.example.tuneplay.databinding.ItemSearchHistoryBinding

/**
 * 搜索历史适配器 — 显示搜索关键词列表，支持点击填入和删除。
 */
class SearchHistoryAdapter(
    private val onItemClick: (String) -> Unit,
    private val onDeleteClick: (String) -> Unit
) : RecyclerView.Adapter<SearchHistoryAdapter.ViewHolder>() {

    private var items: List<SearchHistory> = emptyList()

    fun submitList(list: List<SearchHistory>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSearchHistoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemSearchHistoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: SearchHistory) {
            binding.tvHistoryQuery.text = item.query
            binding.root.setOnClickListener { onItemClick(item.query) }
            binding.btnHistoryDelete.setOnClickListener { onDeleteClick(item.query) }
        }
    }
}
