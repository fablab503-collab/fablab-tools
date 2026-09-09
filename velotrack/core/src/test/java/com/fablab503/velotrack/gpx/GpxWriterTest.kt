package com.fablab503.velotrack.gpx

import com.fablab503.velotrack.model.TrackPoint
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxWriterTest {

    private fun point(timeMs: Long, lat: Double, lon: Double, ele: Double?, segment: Int) =
        TrackPoint(
            timeMs = timeMs,
            lat = lat,
            lon = lon,
            ele = ele,
            speedMps = 4.5f,
            accuracyM = 5f,
            segment = segment,
        )

    private fun writeToString(name: String, points: List<TrackPoint>): String {
        val out = ByteArrayOutputStream()
        GpxWriter.write(out, name, points.asSequence())
        return String(out.toByteArray(), StandardCharsets.UTF_8)
    }

    @Test
    fun headerMetadataAndNameAreWrittenInOrder() {
        val t = 1_788_000_000_000L
        val gpx = writeToString("Morning ride", listOf(point(t, 48.8566, 2.3522, 35.0, 0)))

        assertTrue(gpx.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(gpx.contains("<gpx version=\"1.1\" creator=\"VeloTrack\""))
        assertTrue(gpx.contains("xmlns=\"http://www.topografix.com/GPX/1/1\""))
        assertTrue(gpx.contains("<metadata><time>" + GpxWriter.isoTime(t) + "</time></metadata>"))
        assertTrue(gpx.contains("<trk>"))
        assertTrue(gpx.contains("<name>Morning ride</name>"))
        assertTrue(gpx.trimEnd().endsWith("</gpx>"))

        val iMeta = gpx.indexOf("<metadata>")
        val iTrk = gpx.indexOf("<trk>")
        val iName = gpx.indexOf("<name>")
        val iSeg = gpx.indexOf("<trkseg>")
        val iPt = gpx.indexOf("<trkpt")
        assertTrue(iMeta in 0 until iTrk)
        assertTrue(iTrk < iName)
        assertTrue(iName < iSeg)
        assertTrue(iSeg < iPt)
    }

    @Test
    fun segmentChangeProducesTwoTrksegs() {
        val gpx = writeToString(
            "Ride",
            listOf(
                point(1_000L, 50.0, 14.0, 200.0, 0),
                point(2_000L, 50.001, 14.001, 201.0, 0),
                point(3_000L, 50.002, 14.002, 202.0, 1),
            ),
        )
        assertEquals(2, gpx.split("<trkseg>").size - 1)
        assertEquals(2, gpx.split("</trkseg>").size - 1)
        assertEquals(3, gpx.split("<trkpt ").size - 1)
    }

    @Test
    fun eleBeforeTimeAndSixDecimalCoordinates() {
        val t = 1_788_000_000_000L
        val gpx = writeToString("Ride", listOf(point(t, 48.8566, -2.35, 35.26, 0)))
        val expected = "<trkpt lat=\"48.856600\" lon=\"-2.350000\"><ele>35.3</ele><time>" +
            GpxWriter.isoTime(t) + "</time></trkpt>"
        assertTrue("was: $gpx", gpx.contains(expected))
    }

    @Test
    fun eleOmittedWhenNull() {
        val gpx = writeToString("Ride", listOf(point(0L, 1.0, 2.0, null, 0)))
        assertFalse(gpx.contains("<ele>"))
        assertTrue(gpx.contains("<trkpt lat=\"1.000000\" lon=\"2.000000\"><time>1970-01-01T00:00:00Z</time></trkpt>"))
    }

    @Test
    fun nameIsEscaped() {
        val gpx = writeToString("A & B <C> \"D\" 'E'", listOf(point(0L, 1.0, 2.0, null, 0)))
        assertTrue(gpx.contains("<name>A &amp; B &lt;C&gt; &quot;D&quot; &apos;E&apos;</name>"))
    }

    @Test
    fun escapeXmlReplacesAllSpecials() {
        assertEquals("&amp;&lt;&gt;&quot;&apos;x", GpxWriter.escapeXml("&<>\"'x"))
        assertEquals("plain", GpxWriter.escapeXml("plain"))
    }

    @Test
    fun isoTimeIsUtcSecondPrecision() {
        assertEquals("1970-01-01T00:00:00Z", GpxWriter.isoTime(0L))
        assertEquals("2026-09-07T10:15:30Z", GpxWriter.isoTime(1_788_776_130_000L))
        assertEquals("2026-09-07T10:15:30Z", GpxWriter.isoTime(1_788_776_130_999L))
    }

    @Test
    fun emptyTrackStillProducesValidDocument() {
        val gpx = writeToString("Empty", emptyList())
        assertTrue(gpx.contains("<name>Empty</name>"))
        assertFalse(gpx.contains("<trkseg>"))
        assertTrue(gpx.trimEnd().endsWith("</gpx>"))
    }
}
