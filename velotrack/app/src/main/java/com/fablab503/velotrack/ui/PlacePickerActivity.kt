package com.fablab503.velotrack.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.fablab503.velotrack.R
import com.fablab503.velotrack.databinding.ActivityPlacePickerBinding
import com.fablab503.velotrack.download.Bands
import com.fablab503.velotrack.download.MapLibrary
import com.fablab503.velotrack.geo.Geo
import com.fablab503.velotrack.map.MapController
import com.fablab503.velotrack.model.CameraMode
import com.fablab503.velotrack.model.LatLon
import com.fablab503.velotrack.settings.Prefs
import com.fablab503.velotrack.storage.TrackDatabase
import com.google.android.material.color.MaterialColors
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView

/**
 * Pick a point by dragging the offline map under a fixed crosshair.
 *
 * Exists because the only ways to place Home, Work or a favourite were the current GPS position and
 * the centre of the main map, so setting a place you are not standing in meant panning the main map
 * and guessing. Here the crosshair never moves, the card names whatever road, park or town sits
 * under it, and the point is only saved when the rider presses Save.
 *
 * There is no address search: the app carries no geocoder and the Protomaps basemap has no house
 * numbers, so the honest tool is the map itself plus the name under the crosshair.
 */
class PlacePickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlacePickerBinding
    private lateinit var prefs: Prefs
    private lateinit var mapLibrary: MapLibrary
    private lateinit var mapView: MapView
    private lateinit var mapController: MapController

    private var map: MapLibreMap? = null
    private var startPoint: LatLon? = null

    /** Last point we asked for a name, so panning a few metres does not re-query on every frame. */
    private var lastNamedAt: LatLon? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        mapLibrary = MapLibrary(this, prefs, TrackDatabase.get(this))

        binding = ActivityPlacePickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.picker_title)
        binding.nameInput.setText(intent.getStringExtra(EXTRA_NAME).orEmpty())

        startPoint = readPoint(intent) ?: prefs.lastPosition

        val options = MapLibreMapOptions.createFromAttributes(this)
            .attributionEnabled(false)
            .logoEnabled(false)
            .compassEnabled(false)
            .setPrefetchesTiles(false)
            .crossSourceCollisions(false)
        mapView = MapView(this, options)
        binding.mapContainer.addView(
            mapView,
            0,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        mapView.onCreate(savedInstanceState)

        mapController = MapController(this, mapView, prefs)
        // The whole point is a free camera: never follow the rider here.
        mapController.cameraMode = CameraMode.FREE
        mapController.lightMap = !isNightUi()

        mapView.getMapAsync { m ->
            map = m
            mapController.onMapReady(m)
            applyMapThemeColors()
            val camera = CameraPosition.Builder()
                .target(startPoint?.let { LatLng(it.lat, it.lon) } ?: LatLng(0.0, 0.0))
                .zoom(if (startPoint != null) START_ZOOM else WORLD_ZOOM)
                .tilt(0.0)
                .bearing(0.0)
                .build()
            m.moveCamera(CameraUpdateFactory.newCameraPosition(camera))
            m.addOnCameraIdleListener { refreshPlaceName() }
            loadMapData()
        }

        binding.btnMyPosition.setOnClickListener {
            val here = prefs.lastPosition
            if (here == null) {
                toast(getString(R.string.fav_no_position))
                return@setOnClickListener
            }
            map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(here.lat, here.lon), START_ZOOM))
        }

        binding.btnSave.setOnClickListener { save() }
    }

    private fun save() {
        val centre = map?.cameraPosition?.target
        if (centre == null) {
            toast(getString(R.string.fav_no_map_centre))
            return
        }
        val typed = binding.nameInput.text?.toString()?.trim().orEmpty()
        // An empty label falls back to whatever the map calls this spot, then to the caller's default.
        val name = typed.ifEmpty { binding.placeText.text?.toString()?.trim().orEmpty() }
            .ifEmpty { intent.getStringExtra(EXTRA_NAME).orEmpty() }
        setResult(
            Activity.RESULT_OK,
            Intent()
                .putExtra(EXTRA_LAT, centre.latitude)
                .putExtra(EXTRA_LON, centre.longitude)
                .putExtra(EXTRA_NAME, name),
        )
        finish()
    }

    /** Names the point under the crosshair, but only once the camera has moved a useful distance. */
    private fun refreshPlaceName() {
        val centre = map?.cameraPosition?.target ?: return
        val at = LatLon(centre.latitude, centre.longitude)
        val previous = lastNamedAt
        if (previous != null && Geo.distanceM(previous, at) < MIN_MOVE_M) return
        lastNamedAt = at
        val name = mapController.placeNameAt(at)
        val text = name ?: getString(R.string.picker_unknown_place)
        if (binding.placeText.text?.toString() == text) return
        // Cross-fade so a name changing while you drag does not flicker.
        binding.placeText.animate().cancel()
        binding.placeText.animate()
            .alpha(0f)
            .setDuration(90)
            .withEndAction {
                binding.placeText.text = text
                binding.placeText.animate().alpha(1f).setDuration(120).start()
            }
            .start()
    }

    private fun loadMapData() {
        lifecycleScope.launch {
            val files: List<File> = withContext(Dispatchers.IO) {
                runCatching { mapLibrary.ensureBandFiles() }
                Bands.ALL.map { band -> mapLibrary.bandFile(band) }
            }
            mapController.lightMap = !isNightUi()
            mapController.loadStyle(files) { refreshPlaceName() }
        }
    }

    private fun applyMapThemeColors() {
        val root = binding.root
        val primary = MaterialColors.getColor(root, androidx.appcompat.R.attr.colorPrimary)
        mapController.setThemeColors(
            trackColor = primary,
            routeColor = MaterialColors.getColor(root, com.google.android.material.R.attr.colorTertiary),
            puckColor = primary,
            puckOnColor = MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnPrimary),
        )
    }

    private fun isNightUi(): Boolean =
        resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** Edge to edge: the panels, not the map, carry the system bar insets. */
    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.mapContainer) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            binding.topPanel.updatePadding(top = bars.top)
            binding.controls.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun readPoint(intent: Intent): LatLon? {
        if (!intent.hasExtra(EXTRA_LAT) || !intent.hasExtra(EXTRA_LON)) return null
        val lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
        val lon = intent.getDoubleExtra(EXTRA_LON, Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return null
        return LatLon(lat, lon)
    }

    // ---- MapView lifecycle -----------------------------------------------------------------

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        mapController.onDestroy()
        mapView.onDestroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_NAME = "name"
        const val EXTRA_TITLE = "title"

        /** Close enough to read street names, far enough to recognise where you are. */
        private const val START_ZOOM = 16.0
        private const val WORLD_ZOOM = 2.0

        /** Re-query the name only after the crosshair has moved this far. */
        private const val MIN_MOVE_M = 15.0

        fun intent(context: Context, title: String, name: String, at: LatLon?): Intent =
            Intent(context, PlacePickerActivity::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_NAME, name)
                .apply {
                    if (at != null) {
                        putExtra(EXTRA_LAT, at.lat)
                        putExtra(EXTRA_LON, at.lon)
                    }
                }
    }
}
