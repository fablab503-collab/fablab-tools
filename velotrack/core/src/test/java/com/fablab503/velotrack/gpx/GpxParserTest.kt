package com.fablab503.velotrack.gpx

import com.fablab503.velotrack.model.TrackPoint
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class GpxParserTest {

    private fun parse(xml: String): GpxData =
        GpxParser.parse(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)))

    @Test
    fun parsesNamespacedRoute() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
              <metadata><name>Metadata name</name></metadata>
              <rte>
                <name>Loop</name>
                <rtept lat="48.1" lon="17.1"><ele>150.5</ele></rtept>
                <rtept lat="48.2" lon="17.2"/>
                <rtept lat="48.3" lon="17.3"><ele>152</ele><name>Third</name></rtept>
              </rte>
            </gpx>
        """.trimIndent()
        val data = parse(xml)
        assertEquals("Loop", data.name)
        assertEquals(3, data.points.size)
        assertEquals(3, data.elevations.size)
        assertEquals(48.1, data.points[0].lat, 1e-9)
        assertEquals(17.1, data.points[0].lon, 1e-9)
        assertEquals(48.3, data.points[2].lat, 1e-9)
        assertEquals(17.3, data.points[2].lon, 1e-9)
        assertEquals(150.5, data.elevations[0] ?: Double.NaN, 1e-9)
        assertNull(data.elevations[1])
        assertEquals(152.0, data.elevations[2] ?: Double.NaN, 1e-9)
    }

    @Test
    fun parsesTrackWithTwoSegments() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
              <trk>
                <name>Ride</name>
                <trkseg>
                  <trkpt lat="50.0" lon="14.0"><ele>200.0</ele><time>2026-09-07T10:00:00Z</time></trkpt>
                  <trkpt lat="50.001" lon="14.001"><ele>201.0</ele><time>2026-09-07T10:00:01Z</time></trkpt>
                </trkseg>
                <trkseg>
                  <trkpt lat="50.002" lon="14.002"><time>2026-09-07T10:05:00Z</time></trkpt>
                  <trkpt lat="50.003" lon="14.003"><ele>203.0</ele><time>2026-09-07T10:05:01Z</time></trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()
        val data = parse(xml)
        assertEquals("Ride", data.name)
        assertEquals(4, data.points.size)
        assertEquals(50.003, data.points[3].lat, 1e-9)
        assertEquals(14.003, data.points[3].lon, 1e-9)
        assertNull(data.elevations[2])
        assertEquals(203.0, data.elevations[3] ?: Double.NaN, 1e-9)
    }

    @Test
    fun parsesUnnamespacedAndPrefixedElements() {
        val plain = """
            <gpx version="1.0"><trk><trkseg><trkpt lat="1.5" lon="2.5"/></trkseg></trk></gpx>
        """.trimIndent()
        val data = parse(plain)
        assertNull(data.name)
        assertEquals(1, data.points.size)
        assertEquals(1.5, data.points[0].lat, 1e-9)

        val prefixed = """
            <g:gpx xmlns:g="http://www.topografix.com/GPX/1/1" version="1.1" creator="t">
              <g:rte><g:name>P</g:name><g:rtept lat="3.5" lon="4.5"><g:ele>7</g:ele></g:rtept></g:rte>
            </g:gpx>
        """.trimIndent()
        val prefixedData = parse(prefixed)
        assertEquals("P", prefixedData.name)
        assertEquals(1, prefixedData.points.size)
        assertEquals(4.5, prefixedData.points[0].lon, 1e-9)
        assertEquals(7.0, prefixedData.elevations[0] ?: Double.NaN, 1e-9)
    }

    @Test
    fun rejectsGarbage() {
        try {
            parse("not xml")
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun rejectsDocumentWithoutPoints() {
        try {
            parse("""<gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="t"><wpt lat="1" lon="2"/></gpx>""")
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun rejectsPointWithoutCoordinates() {
        try {
            parse("""<gpx><trk><trkseg><trkpt lat="abc" lon="1"/></trkseg></trk></gpx>""")
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun roundTripPreservesPointsAndCoordinates() {
        val points = (0 until 50).map { i ->
            TrackPoint(
                timeMs = 1_788_776_130_000L + i * 1000L,
                lat = 48.123456 + i * 0.000123,
                lon = -122.654321 - i * 0.000321,
                ele = if (i % 7 == 0) null else 100.0 + i * 0.5,
                speedMps = 5f,
                accuracyM = 4f,
                segment = if (i < 25) 0 else 1,
            )
        }
        val out = ByteArrayOutputStream()
        GpxWriter.write(out, "Round <trip>", points.asSequence())
        val data = GpxParser.parse(ByteArrayInputStream(out.toByteArray()))

        assertEquals("Round <trip>", data.name)
        assertEquals(points.size, data.points.size)
        assertEquals(points.size, data.elevations.size)
        for (i in points.indices) {
            assertEquals(points[i].lat, data.points[i].lat, 1e-6)
            assertEquals(points[i].lon, data.points[i].lon, 1e-6)
            val ele = points[i].ele
            if (ele == null) {
                assertNull(data.elevations[i])
            } else {
                assertEquals(ele, data.elevations[i] ?: Double.NaN, 0.05)
            }
        }
    }
}
