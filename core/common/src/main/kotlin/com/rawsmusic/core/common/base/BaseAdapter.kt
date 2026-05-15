package com.rawsmusic.core.common.base

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding

abstract class BaseAdapter<T, VB : ViewBinding>(
    private val bindingInflater: (ViewGroup, Boolean) -> VB
) : RecyclerView.Adapter<BaseAdapter<T, VB>.BaseViewHolder>() {

    protected val items = mutableListOf<T>()

    inner class BaseViewHolder(val binding: VB) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        val binding = bindingInflater.invoke(parent, false)
        return BaseViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BaseViewHolder, position: Int) {
        if (position in items.indices) {
            onBind(holder.binding, items[position], position)
        }
    }

    override fun getItemCount(): Int = items.size

    protected abstract fun onBind(binding: VB, item: T, position: Int)

    open fun submitList(newItems: List<T>) {
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = items.size
            override fun getNewListSize(): Int = newItems.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                areItemsSame(items[oldPos], newItems[newPos])
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                areContentsSame(items[oldPos], newItems[newPos])
        })
        items.clear()
        items.addAll(newItems)
        diffResult.dispatchUpdatesTo(this)
    }

    open fun areItemsSame(oldItem: T, newItem: T): Boolean = oldItem == newItem

    open fun areContentsSame(oldItem: T, newItem: T): Boolean = oldItem == newItem

    fun getItem(position: Int): T? =
        if (position in items.indices) items[position] else null

    fun addAll(newItems: List<T>) {
        val startPos = items.size
        items.addAll(newItems)
        notifyItemRangeInserted(startPos, newItems.size)
    }

    fun clear() {
        val size = items.size
        items.clear()
        notifyItemRangeRemoved(0, size)
    }

    fun removeAt(position: Int) {
        if (position in items.indices) {
            items.removeAt(position)
            notifyItemRemoved(position)
        }
    }
}
