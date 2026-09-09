package com.fablab503.velotrack.ui

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.fablab503.velotrack.R
import com.fablab503.velotrack.databinding.DialogAddFavoriteBinding
import com.fablab503.velotrack.model.Favorite
import com.fablab503.velotrack.model.FavoriteKind
import com.fablab503.velotrack.model.LatLon
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** What the user entered in [AddFavoriteDialog]; the caller writes it to the database. */
data class FavoriteInput(
    val name: String,
    val description: String,
    val kind: FavoriteKind,
    val location: LatLon,
    /** Marker emoji, or null to keep drawing [kind]'s own icon on the map. */
    val emoji: String? = null,
)

/**
 * Add / edit dialog for a favourite: name, description, kind chips and the location source
 * (keep the current one when editing, my position, or the map centre). Validation keeps the
 * dialog open until the input is complete.
 */
object AddFavoriteDialog {

    /**
     * @param existing the favourite being edited, or null to add a new one
     * @param myPosition the rider's last fix, or null when there is none yet
     * @param mapCentre where the map picker should open, or null before the map is ready
     * @param onSave the favourite is complete and can be written
     * @param onPickOnMap the rider chose "Choose on map": everything but the location is decided,
     *   and the caller opens [PlacePickerActivity] and saves once a point comes back
     */
    fun show(
        context: Context,
        existing: Favorite?,
        myPosition: LatLon?,
        mapCentre: LatLon?,
        onSave: (FavoriteInput) -> Unit,
        onPickOnMap: (name: String, description: String, kind: FavoriteKind, emoji: String?, startAt: LatLon?) -> Unit,
    ) {
        val b = DialogAddFavoriteBinding.inflate(LayoutInflater.from(context))
        val fixedKind = existing != null && (existing.kind == FavoriteKind.HOME || existing.kind == FavoriteKind.WORK)

        if (existing != null) {
            b.nameInput.setText(existing.name)
            b.nameInput.setSelection(existing.name.length)
            b.descriptionInput.setText(existing.description)
            b.btnLocKeep.isVisible = true
        }
        // Home and Work keep their kind; the chips only cover the free kinds.
        b.kindLabel.isVisible = !fixedKind
        b.kindGroup.isVisible = !fixedKind
        b.kindGroup.check(
            when (existing?.kind) {
                FavoriteKind.RESTAURANT -> R.id.chipRestaurant
                FavoriteKind.THEATRE -> R.id.chipTheatre
                FavoriteKind.PLACE -> R.id.chipPlace
                else -> R.id.chipPerson
            }
        )

        // The custom field only ever holds an emoji that is NOT one of the quick-pick chips --
        // otherwise editing a favourite whose emoji is a suggestion would show it twice.
        var customEmoji = existing?.emoji?.takeIf { it !in EmojiPicker.SUGGESTIONS }
        var chipEmoji = existing?.emoji?.takeIf { it in EmojiPicker.SUGGESTIONS }
        b.emojiCustomInput.setText(customEmoji.orEmpty())
        EmojiPicker.populate(b.emojiGroup, chipEmoji) { picked ->
            chipEmoji = picked
            if (picked != null) {
                // Picking a suggestion clears any custom text so the two cannot disagree.
                customEmoji = null
                b.emojiCustomInput.setText("")
            }
        }
        b.emojiCustomInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val typed = s?.toString()?.trim().orEmpty()
                customEmoji = typed.ifEmpty { null }
                if (typed.isNotEmpty() && b.emojiGroup.checkedChipId != View.NO_ID) {
                    // Typing a custom emoji clears the chip selection the same way round.
                    chipEmoji = null
                    b.emojiGroup.clearCheck()
                }
            }
        })

        b.btnLocHere.isEnabled = myPosition != null
        when {
            existing != null -> b.locationGroup.check(R.id.btnLocKeep)
            myPosition != null -> b.locationGroup.check(R.id.btnLocHere)
            else -> b.locationGroup.check(R.id.btnLocCentre)
        }
        b.locationGroup.addOnButtonCheckedListener { _, _, _ -> b.locationHint.isVisible = false }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(if (existing == null) R.string.fav_add else R.string.fav_edit_title)
            .setView(b.root)
            .setPositiveButton(R.string.dialog_ok, null)
            .setNegativeButton(R.string.dialog_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = b.nameInput.text?.toString()?.trim() ?: ""
                if (name.isEmpty()) {
                    b.nameLayout.error = context.getString(R.string.fav_name_required)
                    return@setOnClickListener
                }
                b.nameLayout.error = null

                // fixedKind (line 46) is already `existing != null && ...`, so `existing?.kind`
                // is non-null exactly when fixedKind is true; this reads the same as the old
                // `if (fixedKind && existing != null) existing.kind else ...` without the
                // redundant null check the compiler flagged.
                val kind = existing?.kind?.takeIf { fixedKind } ?: when (b.kindGroup.checkedChipId) {
                    R.id.chipRestaurant -> FavoriteKind.RESTAURANT
                    R.id.chipTheatre -> FavoriteKind.THEATRE
                    R.id.chipPlace -> FavoriteKind.PLACE
                    else -> FavoriteKind.PERSON
                }
                val description = b.descriptionInput.text?.toString()?.trim() ?: ""
                val emoji = customEmoji ?: chipEmoji

                if (b.locationGroup.checkedButtonId == R.id.btnLocCentre) {
                    // Hand over to the map picker; the caller saves when a point comes back.
                    dialog.dismiss()
                    onPickOnMap(name, description, kind, emoji, existing?.latLon ?: mapCentre ?: myPosition)
                    return@setOnClickListener
                }

                val location: LatLon? = when (b.locationGroup.checkedButtonId) {
                    R.id.btnLocKeep -> existing?.latLon
                    R.id.btnLocHere -> myPosition
                    else -> null
                }
                if (location == null) {
                    b.locationHint.setText(R.string.fav_no_position)
                    b.locationHint.isVisible = true
                    return@setOnClickListener
                }

                onSave(FavoriteInput(name, description, kind, location, emoji))
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
