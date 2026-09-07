package com.fablab503.velotrack.ui

import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.fablab503.velotrack.R
import com.fablab503.velotrack.databinding.ItemListRowBinding
import com.fablab503.velotrack.databinding.SheetFavoritesBinding
import com.fablab503.velotrack.geo.Geo
import com.fablab503.velotrack.model.Favorite
import com.fablab503.velotrack.model.FavoriteKind
import com.fablab503.velotrack.model.LatLon
import com.fablab503.velotrack.settings.Prefs
import com.fablab503.velotrack.storage.FavoritesRepository
import com.fablab503.velotrack.storage.TrackDatabase
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bottom sheet listing every favourite (Home and Work first). The sheet only reads the database;
 * every change goes through the hosting activity's [Listener], which calls [reload] when done.
 * Tap a row to start guidance, long-press (or the trailing icon) for Edit / Delete.
 */
class FavoritesSheet : BottomSheetDialogFragment() {

    /** Implemented by the hosting activity. */
    interface Listener {
        /** The rider's last known position, for the distance shown in each row. */
        fun favoritesPosition(): LatLon?
        fun onFavoriteChosen(fav: Favorite)
        fun onAddFavorite(sheet: FavoritesSheet)
        fun onEditFavorite(sheet: FavoritesSheet, fav: Favorite)
        fun onDeleteFavorite(sheet: FavoritesSheet, fav: Favorite)
    }

    private var binding: SheetFavoritesBinding? = null
    private var listener: Listener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = context as? Listener
    }

    override fun onDetach() {
        listener = null
        super.onDetach()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = SheetFavoritesBinding.inflate(inflater, container, false)
        binding = b
        b.btnAdd.setOnClickListener { listener?.onAddFavorite(this) }
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        reload()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    /** Re-reads the favourites off the main thread and rebuilds the rows. Safe to call any time. */
    fun reload() {
        val ctx = context ?: return
        if (binding == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                runCatching { FavoritesRepository(TrackDatabase.get(ctx)).list() }.getOrDefault(emptyList())
            }
            val b = binding ?: return@launch
            render(b, items)
        }
    }

    private fun render(b: SheetFavoritesBinding, items: List<Favorite>) {
        val ctx = b.root.context
        val units = Prefs(ctx).units
        val here = listener?.favoritesPosition()
        val inflater = LayoutInflater.from(ctx)
        val background = selectableBackground(ctx)
        b.list.removeAllViews()
        b.emptyText.isVisible = items.isEmpty()
        for (fav in items) {
            val row = ItemListRowBinding.inflate(inflater, b.list, false)
            row.leadingIcon.setImageResource(iconFor(fav.kind))
            row.title.text = fav.name
            val distance = if (here != null) {
                Format.distance(Geo.distanceM(here, fav.latLon), units)
            } else {
                getString(R.string.fav_distance_unknown)
            }
            row.subtitle.text = if (fav.description.isBlank()) {
                distance
            } else {
                getString(R.string.fav_subtitle, fav.description, distance)
            }
            row.trailingIcon.setImageResource(R.drawable.ic_more_vert)
            row.trailingIcon.contentDescription = getString(R.string.cd_favorite_actions)
            row.trailingIcon.isVisible = true
            row.trailingIcon.setOnClickListener { showActions(it, fav) }
            if (background != 0) row.root.setBackgroundResource(background)
            row.root.setOnClickListener {
                listener?.onFavoriteChosen(fav)
                dismiss()
            }
            row.root.setOnLongClickListener {
                showActions(it, fav)
                true
            }
            b.list.addView(row.root)
        }
    }

    private fun showActions(anchor: View, fav: Favorite) {
        val popup = PopupMenu(anchor.context, anchor)
        popup.menu.add(0, MENU_EDIT, 0, R.string.fav_edit)
        popup.menu.add(0, MENU_DELETE, 1, R.string.fav_delete)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_EDIT -> listener?.onEditFavorite(this, fav)
                MENU_DELETE -> listener?.onDeleteFavorite(this, fav)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    private fun selectableBackground(ctx: Context): Int {
        val tv = TypedValue()
        return if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) tv.resourceId else 0
    }

    companion object {
        const val TAG = "favorites_sheet"
        private const val MENU_EDIT = 1
        private const val MENU_DELETE = 2

        /** Drawable for a favourite kind (the same Material Symbols glyphs the map markers use). */
        fun iconFor(kind: FavoriteKind): Int = when (kind) {
            FavoriteKind.HOME -> R.drawable.ic_home
            FavoriteKind.WORK -> R.drawable.ic_work
            FavoriteKind.PERSON -> R.drawable.ic_person
            FavoriteKind.RESTAURANT -> R.drawable.ic_restaurant
            FavoriteKind.THEATRE -> R.drawable.ic_theater_comedy
            FavoriteKind.PLACE -> R.drawable.ic_place
        }
    }
}
