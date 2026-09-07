package com.fablab503.velotrack.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import com.fablab503.velotrack.databinding.ItemListRowBinding

/**
 * ListView adapter for the Material 3 two-line row (`item_list_row.xml`). [bind] fills the row
 * for one item; it must set every view it uses because rows are recycled.
 */
class ListRowAdapter<T : Any>(
    context: Context,
    private val bind: (ItemListRowBinding, T) -> Unit,
) : BaseAdapter() {

    private val inflater = LayoutInflater.from(context)

    var items: List<T> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): T = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = false

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val binding = (convertView?.tag as? ItemListRowBinding)
            ?: ItemListRowBinding.inflate(inflater, parent, false).also { it.root.tag = it }
        bind(binding, items[position])
        return binding.root
    }
}
