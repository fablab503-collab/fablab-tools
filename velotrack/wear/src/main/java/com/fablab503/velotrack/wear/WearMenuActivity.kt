package com.fablab503.velotrack.wear

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.fablab503.velotrack.model.TrackSummary
import com.fablab503.velotrack.storage.TrackDatabase
import com.fablab503.velotrack.storage.TrackRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The watch's menu: what it has recorded, and which build is on the wrist.
 *
 * The rides come from the watch's **own** database through the shared [TrackRepository] - the same
 * rows the ride engine wrote, not a copy pushed over from the phone. That is the point of the watch
 * recording standalone: it has its own history whether or not a phone has ever been near it.
 */
class WearMenuActivity : ComponentActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wear_menu)
        container = findViewById(R.id.menuContainer)
        findViewById<TextView>(R.id.versionText).text = getString(R.string.wear_version, versionName())
        loadRides()
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private fun loadRides() {
        lifecycleScope.launch {
            val rides = withContext(Dispatchers.IO) {
                runCatching { TrackRepository(TrackDatabase.get(this@WearMenuActivity)).listTracks() }
                    .getOrDefault(emptyList())
            }
            if (isFinishing || isDestroyed) return@launch
            render(rides)
        }
    }

    private fun render(rides: List<TrackSummary>) {
        container.removeAllViews()
        if (rides.isEmpty()) {
            container.addView(line(getString(R.string.wear_no_rides), dim = true))
            return
        }
        val stamp = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
        for (ride in rides.take(MAX_SHOWN)) {
            val km = ride.distanceM / 1000.0
            container.addView(
                line(String.format(Locale.US, "%.2f km  ·  %s", km, stamp.format(Date(ride.startedAtMs))))
            )
        }
        if (rides.size > MAX_SHOWN) {
            container.addView(line(getString(R.string.wear_more_rides, rides.size - MAX_SHOWN), dim = true))
        }
    }

    private fun line(text: String, dim: Boolean = false): TextView =
        TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).also { it.topMargin = 4 }
            this.text = text
            gravity = Gravity.CENTER
            textSize = if (dim) 11f else 13f
            setTextColor(getColor(if (dim) R.color.wear_dim else R.color.wear_text))
        }

    private companion object {
        /** A wrist is not a list view. Enough to confirm the last few rides are really there. */
        const val MAX_SHOWN = 8
    }
}
