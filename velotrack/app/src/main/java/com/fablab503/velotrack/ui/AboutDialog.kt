package com.fablab503.velotrack.ui

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.widget.TextView
import com.fablab503.velotrack.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Version, licences and OpenStreetMap attribution. */
object AboutDialog {
    fun show(activity: Activity) {
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.about_title)
            .setMessage(activity.getString(R.string.about_body, versionName(activity)))
            .setPositiveButton(R.string.dialog_close, null)
            .show()
        val messageView: TextView? = dialog.findViewById(android.R.id.message)
        if (messageView != null) {
            Linkify.addLinks(messageView, Linkify.WEB_URLS)
            messageView.movementMethod = LinkMovementMethod.getInstance()
        }
    }

    @Suppress("DEPRECATION")
    private fun versionName(activity: Activity): String {
        val pm = activity.packageManager
        val name: String? = runCatching {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(activity.packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                pm.getPackageInfo(activity.packageName, 0)
            }
            info.versionName
        }.getOrNull()
        return name ?: "?"
    }
}
