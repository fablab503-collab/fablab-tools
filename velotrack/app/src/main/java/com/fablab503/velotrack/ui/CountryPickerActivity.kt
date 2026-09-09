package com.fablab503.velotrack.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.fablab503.velotrack.R
import com.fablab503.velotrack.databinding.ActivityCountryPickerBinding
import com.fablab503.velotrack.databinding.ItemListRowBinding
import com.fablab503.velotrack.download.Countries
import java.util.Locale

/**
 * Picks one of the 195 UN member and observer states by name, filtered as the rider types. There
 * is no map here and no crosshair: a whole country is too large a shape to place by dragging, so
 * this is a plain search-and-pick list, unlike [PlacePickerActivity].
 */
class CountryPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCountryPickerBinding
    private lateinit var adapter: ListRowAdapter<Countries.Country>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCountryPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = ListRowAdapter(this) { row, country -> bindRow(row, country) }
        binding.list.adapter = adapter
        binding.list.setOnItemClickListener { _, _, position, _ -> pick(adapter.getItem(position)) }

        showResults(Countries.ALL)
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = showResults(filter(s?.toString().orEmpty()))
        })
    }

    private fun filter(query: String): List<Countries.Country> {
        val q = query.trim().lowercase(Locale.getDefault())
        if (q.isEmpty()) return Countries.ALL
        return Countries.ALL.filter {
            it.countryName.lowercase(Locale.getDefault()).contains(q) || it.code.lowercase(Locale.getDefault()) == q
        }
    }

    private fun showResults(results: List<Countries.Country>) {
        adapter.items = results
        binding.emptyText.isVisible = results.isEmpty()
        binding.list.isVisible = results.isNotEmpty()
    }

    private fun bindRow(row: ItemListRowBinding, country: Countries.Country) {
        row.leadingIcon.setImageResource(R.drawable.ic_public)
        row.title.text = country.countryName
        row.subtitle.text = country.code
        row.trailingIcon.isVisible = false
    }

    private fun pick(country: Countries.Country) {
        setResult(Activity.RESULT_OK, resultIntent(country))
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        private const val EXTRA_CODE = "code"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_WEST = "west"
        private const val EXTRA_SOUTH = "south"
        private const val EXTRA_EAST = "east"
        private const val EXTRA_NORTH = "north"

        fun intent(context: Context): Intent = Intent(context, CountryPickerActivity::class.java)

        private fun resultIntent(country: Countries.Country): Intent =
            Intent()
                .putExtra(EXTRA_CODE, country.code)
                .putExtra(EXTRA_NAME, country.countryName)
                .putExtra(EXTRA_WEST, country.west)
                .putExtra(EXTRA_SOUTH, country.south)
                .putExtra(EXTRA_EAST, country.east)
                .putExtra(EXTRA_NORTH, country.north)

        /** Rebuilds the picked [Countries.Country] from the result [Intent], or null if malformed. */
        fun countryFrom(data: Intent): Countries.Country? {
            val code = data.getStringExtra(EXTRA_CODE) ?: return null
            val name = data.getStringExtra(EXTRA_NAME) ?: return null
            if (!data.hasExtra(EXTRA_WEST) || !data.hasExtra(EXTRA_SOUTH) ||
                !data.hasExtra(EXTRA_EAST) || !data.hasExtra(EXTRA_NORTH)
            ) {
                return null
            }
            return Countries.Country(
                code = code,
                countryName = name,
                west = data.getDoubleExtra(EXTRA_WEST, Double.NaN),
                south = data.getDoubleExtra(EXTRA_SOUTH, Double.NaN),
                east = data.getDoubleExtra(EXTRA_EAST, Double.NaN),
                north = data.getDoubleExtra(EXTRA_NORTH, Double.NaN),
            ).takeIf { !it.west.isNaN() && !it.south.isNaN() && !it.east.isNaN() && !it.north.isNaN() }
        }
    }
}
