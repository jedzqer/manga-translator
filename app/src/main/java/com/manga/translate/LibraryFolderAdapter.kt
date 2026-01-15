package com.manga.translate

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.manga.translate.databinding.ItemFolderBinding

class LibraryFolderAdapter(
    private val onClick: (FolderItem) -> Unit,
    private val onDelete: (FolderItem) -> Unit
) : RecyclerView.Adapter<LibraryFolderAdapter.FolderViewHolder>() {
    private val items = ArrayList<FolderItem>()
    private var deletePosition: Int? = null

    fun submit(list: List<FolderItem>) {
        val oldSize = items.size
        items.clear()
        items.addAll(list)
        if (oldSize > 0) {
            notifyItemRangeRemoved(0, oldSize)
        }
        if (items.isNotEmpty()) {
            notifyItemRangeInserted(0, items.size)
        }
    }

    fun clearDeleteSelection() {
        val previous = deletePosition
        deletePosition = null
        if (previous != null) {
            notifyItemChanged(previous)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val binding = ItemFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FolderViewHolder(binding, onClick, onDelete, ::toggleDeletePosition)
    }

    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        holder.bind(items[position], position == deletePosition)
    }

    override fun getItemCount(): Int = items.size

    class FolderViewHolder(
        private val binding: ItemFolderBinding,
        private val onClick: (FolderItem) -> Unit,
        private val onDelete: (FolderItem) -> Unit,
        private val onToggleDelete: (Int) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: FolderItem, showDelete: Boolean) {
            binding.folderName.text = item.folder.name
            binding.folderMeta.text = binding.root.context.getString(
                R.string.folder_image_count,
                item.imageCount
            )
            binding.folderDelete.visibility = if (showDelete) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener {
                onToggleDelete(bindingAdapterPosition)
                true
            }
            binding.folderDelete.setOnClickListener { onDelete(item) }
        }
    }

    private fun toggleDeletePosition(position: Int) {
        if (position == RecyclerView.NO_POSITION) return
        val previous = deletePosition
        deletePosition = if (previous == position) null else position
        if (previous != null) {
            notifyItemChanged(previous)
        }
        notifyItemChanged(position)
    }
}
