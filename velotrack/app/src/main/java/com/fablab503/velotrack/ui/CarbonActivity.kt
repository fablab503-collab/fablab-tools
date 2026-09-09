package com.fablab503.velotrack.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.fablab503.velotrack.R
import com.fablab503.velotrack.databinding.ActivityCarbonBinding
import com.fablab503.velotrack.green.CarbonEstimate
import com.fablab503.velotrack.model.RideTotals
import com.fablab503.velotrack.settings.Prefs
import com.fablab503.velotrack.storage.StatsRepository
import com.fablab503.velotrack.storage.TrackDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The working behind the CO2 figure on the map.
 *
 * The headline is deliberately a statement about cars - what one would have emitted over the
 * distance ridden - and not a saving credited to the rider. Riding avoids emissions rather than
 * removing them, and it only avoids anything at all when it replaces a car journey, which no
 * published study establishes for recreational riding. The rider can say how much of their riding
 * replaced a drive, and only then does the screen show an avoided figure.
 *
 * Every constant is printed with its source and year on this screen, next to the number it
 * produced, so a figure can be checked rather than believed.
 */
class CarbonActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCarbonBinding
    private lateinit var prefs: Prefs
    private var totals: RideTotals = RideTotals.EMPTY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        binding = ActivityCarbonBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.substitutionSlider.addOnChangeListener { _, value, _ ->
            renderAvoided(value / 100.0)
        }
        binding.sourcesBody.text = getString(
            R.string.carbon_sources_body,
            CarbonEstimate.CAR_G_PER_KM,
            CarbonEstimate.BIKE_G_PER_KM,
            (CarbonEstimate.COMMUTE_SUBSTITUTION * 100).toInt(),
        )
        load()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun load() {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { StatsRepository(TrackDatabase.get(this@CarbonActivity)).totals(null) }
                    .getOrDefault(RideTotals.EMPTY)
            }
            if (isFinishing || isDestroyed) return@launch
            totals = loaded
            render()
        }
    }

    private fun render() {
        val km = totals.distanceM / 1000.0
        val kg = CarbonEstimate.carEquivalentKg(totals.distanceM)
        binding.headlineKg.text = getString(R.string.carbon_kg, kg)
        binding.headlineCaption.text = getString(
            R.string.carbon_headline_caption,
            Format.distance(totals.distanceM, prefs.units),
        )
        binding.headlineEquiv.text = if (km < 1.0) {
            getString(R.string.carbon_ride_more)
        } else {
            getString(
                R.string.carbon_equivalences,
                CarbonEstimate.petrolLitres(totals.distanceM),
                CarbonEstimate.phoneCharges(kg).toInt(),
            )
        }
        renderAvoided(binding.substitutionSlider.value / 100.0)
    }

    private fun renderAvoided(substitution: Double) {
        val avoided = CarbonEstimate.avoidedKg(totals.distanceM, substitution)
        binding.substitutionLabel.text = if (substitution <= 0.0) {
            getString(R.string.carbon_avoided_none)
        } else {
            getString(
                R.string.carbon_avoided_value,
                (substitution * 100).toInt(),
                String.format(Locale.getDefault(), "%.1f", avoided),
            )
        }
    }
}
