package com.fablab503.velotrack.download

/**
 * The one thing every *optional*, opt-in-by-use network call in this app (weather, address
 * search) must send: a real identifying User-Agent. Both providers this app talks to police it --
 * met.no returns a hard 403 for OkHttp's own default string, and Nominatim's usage policy makes
 * "provide a valid User-Agent" a condition of using the public server at all. A generic library
 * default identifies nobody and gets nobody unblocked when something goes wrong on their end.
 *
 * This says nothing about map tiles or Play uploads, which are core to the app and already worked
 * before either feature existed; it exists for the network calls that are new, occasional, and
 * only ever made because the rider tapped something.
 */
internal const val APP_USER_AGENT = "VeloTrack/1.0 (+https://github.com/fablab503-collab/fablab-tools)"
