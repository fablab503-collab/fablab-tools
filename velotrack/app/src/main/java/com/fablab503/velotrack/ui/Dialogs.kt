package com.fablab503.velotrack.ui

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** A modal, non-cancelable progress dialog whose message can be updated. */
class ProgressDialogHandle(private val dialog: AlertDialog, private val text: TextView) {
    fun setMessage(message: CharSequence) {
        text.text = message
    }

    fun dismiss() {
        if (dialog.isShowing) dialog.dismiss()
    }
}

fun Context.showProgressDialog(message: CharSequence): ProgressDialogHandle {
    val density = resources.displayMetrics.density
    val pad = (20 * density).toInt()
    val barSize = (40 * density).toInt()
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(pad, pad, pad, pad)
    }
    row.addView(ProgressBar(this), LinearLayout.LayoutParams(barSize, barSize))
    val text = TextView(this).apply {
        this.text = message
        textSize = 16f
        setPadding(pad, 0, 0, 0)
    }
    row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val dialog = MaterialAlertDialogBuilder(this)
        .setView(row)
        .setCancelable(false)
        .create()
    dialog.show()
    return ProgressDialogHandle(dialog, text)
}
