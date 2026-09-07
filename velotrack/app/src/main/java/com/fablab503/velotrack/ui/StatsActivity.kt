package com.fablab503.velotrack.ui

import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.fablab503.velotrack.R
import com.fablab503.velotrack.databinding.ActivityStatsBinding
import com.fablab503.velotrack.databinding.ItemStatsCardBinding
import com.fablab503.velotrack.model.RideTotals
import com.fablab503.velotrack.model.Units
import com.fablab503.velotrack.settings.Prefs
import com.fablab503.velotrack.storage.StatsRepository
import com.fablab503.velotrack.storage.TrackDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Riding totals over four periods (all time, this year, this month, this week), aggregated from the
 * finished tracks. Shows an empty state until the first ride has been recorded.
 */
class StatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStatsBinding
    private lateinit var prefs: Prefs
    private lateinit var repo: StatsRepository

    /** The four aggregates loaded together on the IO dispatcher. */
    private class Snapshot(
        val all: RideTotals,
        val year: RideTotals,
        val month: RideTotals,
        val week: RideTotals,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        repo = StatsRepository(TrackDatabase.get(this))

        binding = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupCard(binding.cardAllTime, R.drawable.img3d_trophy, R.string.stats_all_time)
        setupCard(binding.cardYear, R.drawable.img3d_calender, R.string.stats_this_year)
        setupCard(binding.cardMonth, R.drawable.img3d_fire, R.string.stats_this_month)
        setupCard(binding.cardWeek, R.drawable.img3d_flash, R.string.stats_this_week)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun setupCard(card: ItemStatsCardBinding, @DrawableRes icon: Int, @StringRes title: Int) {
        card.icon.setImageResource(icon)
        card.periodTitle.setText(title)
    }

    private fun reload() {
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val snapshot = withContext(Dispatchers.IO) {
                runCatching {
                    Snapshot(
                        all = repo.totals(null),
                        year = repo.totals(StatsRepository.startOfYearMs(now)),
                        month = repo.totals(StatsRepository.startOfMonthMs(now)),
                        week = repo.totals(StatsRepository.startOfWeekMs(now)),
                    )
                }.getOrNull()
            }
            render(snapshot)
        }
    }

    private fun render(snapshot: Snapshot?) {
        val empty = snapshot == null || snapshot.all.rides == 0
        binding.emptyGroup.isVisible = empty
        binding.statsContent.isVisible = !empty
        if (snapshot == null || empty) return

        val units: Units = prefs.units
        binding.totalHeadline.text = getString(R.string.stats_total_headline, Format.distance(snapshot.all.distanceM, units))
        bindCard(binding.cardAllTime, snapshot.all, units)
        bindCard(binding.cardYear, snapshot.year, units)
        bindCard(binding.cardMonth, snapshot.month, units)
        bindCard(binding.cardWeek, snapshot.week, units)
    }

    private fun bindCard(card: ItemStatsCardBinding, t: RideTotals, units: Units) {
        card.distance.text = Format.distance(t.distanceM, units)
        card.rides.text = if (t.rides == 1) getString(R.string.stats_ride_one) else getString(R.string.stats_rides, t.rides)
        card.moving.text = getString(R.string.stats_moving, Format.duration(t.movingMs))
        card.climb.text = getString(R.string.stats_climb, Format.elevation(t.elevationGainM, units))
        card.longest.text = getString(R.string.stats_longest, Format.distance(t.longestDistanceM, units))
    }
}
