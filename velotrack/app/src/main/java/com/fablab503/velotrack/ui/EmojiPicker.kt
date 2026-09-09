package com.fablab503.velotrack.ui

import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * The quick-pick row shared by [AddFavoriteDialog] and Home/Work's set dialog: a chip per common
 * place emoji (the chip's own text, large, so the row is its own preview), plus a free-text field
 * next to it for anything not on this short list. A rider is never limited to these -- they are a
 * shortcut, not the whole picker.
 */
object EmojiPicker {

    /** Ordinary bike-touring places, roughly in order of how often a rider would want them. */
    val SUGGESTIONS = listOf(
        "🏠", // house (Home)
        "🏢", // office building (Work)
        "⭐",       // star
        "☕",       // coffee
        "🍽️", // fork and plate (restaurant)
        "🎭", // performing arts masks (theatre)
        "🏋️", // weight lifter (gym)
        "🏥", // hospital
        "🌳", // tree (park)
        "🚲", // bicycle
        "🅿️", // parking
        "🛍️", // shopping bags
    )

    /**
     * Fills [group] with one non-checkable-looking-but-checkable [Chip] per [SUGGESTIONS] entry,
     * pre-checking [selected] if it is one of them. [onPicked] fires with the emoji on selection,
     * or null when the rider taps the already-checked chip to clear it.
     */
    fun populate(group: ChipGroup, selected: String?, onPicked: (String?) -> Unit) {
        group.removeAllViews()
        val context = group.context
        for (emoji in SUGGESTIONS) {
            val chip = Chip(context).apply {
                text = emoji
                textSize = 18f
                isCheckable = true
                isChecked = emoji == selected
                id = ChipGroup.generateViewId()
            }
            group.addView(chip)
        }
        group.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedChip = checkedIds.firstOrNull()?.let { group.findViewById<Chip>(it) }
            onPicked(checkedChip?.text?.toString())
        }
    }
}
