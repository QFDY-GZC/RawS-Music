package com.rawsmusic.core.common.base

import android.view.ViewGroup
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding

abstract class BaseAdapter<T, VB : ViewBinding>(
    private val bindingInflater: (ViewGroup, Boolean) -> VB
) : RecyclerView.Adapter<BaseAdapter<T, VB>.BaseViewHolder>() {

    // 使用 AsyncListDiffer 在后台线程计算 Diff，避免阻塞主线程
    private val differ = AsyncListDiffer(this, object : DiffUtil.ItemCallback<T>() {
        override fun areItemsTheSame(oldItem: T & Any, newItem: T & Any): Boolean =
            areItemsSame(oldItem, newItem)

        override fun areContentsTheSame(oldItem: T & Any, newItem: T & Any): Boolean =
            areContentsSame(oldItem, newItem)
    })

    // 兼容旧代码：提供 items 访问
    protected val items: List<T>
        get() = differ.currentList

    inner class BaseViewHolder(val binding: VB) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        val binding = bindingInflater.invoke(parent, false)
        return BaseViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BaseViewHolder, position: Int) {
        val currentList = differ.currentList
        if (position in currentList.indices) {
            onBind(holder.binding, currentList[position], position)
        }
    }

    override fun getItemCount(): Int = differ.currentList.size

    protected abstract fun onBind(binding: VB, item: T, position: Int)

    /**
     * 提交新列表，Diff 计算在后台线程异步执行。
     * 1000+ 首歌曲时不会阻塞主线程。
     */
    open fun submitList(newItems: List<T>) {
        differ.submitList(newItems)
    }

    open fun areItemsSame(oldItem: T, newItem: T): Boolean = oldItem == newItem

    open fun areContentsSame(oldItem: T, newItem: T): Boolean = oldItem == newItem

    fun getItem(position: Int): T? =
        differ.currentList.getOrNull(position)

    fun addAll(newItems: List<T>) {
        val combined = differ.currentList + newItems
        differ.submitList(combined)
    }

    fun clear() {
        differ.submitList(emptyList())
    }

    fun removeAt(position: Int) {
        val currentList = differ.currentList.toMutableList()
        if (position in currentList.indices) {
            currentList.removeAt(position)
            differ.submitList(currentList)
        }
    }
}
