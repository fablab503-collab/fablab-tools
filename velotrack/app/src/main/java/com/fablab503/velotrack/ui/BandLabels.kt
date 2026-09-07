package com.fablab503.velotrack.ui

import android.content.Context
import com.fablab503.velotrack.R
import com.fablab503.velotrack.download.Bands

/** User-facing name of a detail band: 0 Overview, 1 Region, 2 Roads, 3 Streets. */
fun Context.bandLabel(bandIndex: Int): String = when (bandIndex) {
    0 -> getString(R.string.band_label_0)
    1 -> getString(R.string.band_label_1)
    2 -> getString(R.string.band_label_2)
    else -> getString(R.string.band_label_3)
}

/** The band with this index, or the finest band when the index is out of range. */
fun bandOrDefault(bandIndex: Int): Bands.Band =
    Bands.ALL.firstOrNull { it.index == bandIndex } ?: Bands.ALL.last()
