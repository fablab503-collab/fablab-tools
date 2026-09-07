package com.fablab503.velotrack.ui

import android.content.Context
import android.view.LayoutInflater
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
     * @param mapCentre the camera target, or null before the map is ready
     */
    fun show(
        context: Context,
        existing: Favorite?,
        myPosition: LatLon?,
        mapCentre: LatLon?,
        onSave: (FavoriteInput) -> Unit,
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

        b.btnLocHere.isEnabled = myPosition != null
        b.btnLocCentre.isEnabled = mapCentre != null
        when {
            existing != null -> b.locationGroup.check(R.id.btnLocKeep)
            myPosition != null -> b.locationGroup.check(R.id.btnLocHere)
            mapCentre != null -> b.locationGroup.check(R.id.btnLocCentre)
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

                val location: LatLon? = when (b.locationGroup.checkedButtonId) {
                    R.id.btnLocKeep -> existing?.latLon
                    R.id.btnLocHere -> myPosition
                    R.id.btnLocCentre -> mapCentre
                    else -> null
                }
                if (location == null) {
                    b.locationHint.setText(
                        if (b.locationGroup.checkedButtonId == R.id.btnLocCentre) {
                            R.string.fav_no_map_centre
                        } else {
                            R.string.fav_no_position
                        }
                    )
                    b.locationHint.isVisible = true
                    return@setOnClickListener
                }

                val kind = if (fixedKind && existing != null) {
                    existing.kind
                } else {
                    when (b.kindGroup.checkedChipId) {
                        R.id.chipRestaurant -> FavoriteKind.RESTAURANT
                        R.id.chipTheatre -> FavoriteKind.THEATRE
                        R.id.chipPlace -> FavoriteKind.PLACE
                        else -> FavoriteKind.PERSON
                    }
                }
                val description = b.descriptionInput.text?.toString()?.trim() ?: ""
                onSave(FavoriteInput(name, description, kind, location))
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
