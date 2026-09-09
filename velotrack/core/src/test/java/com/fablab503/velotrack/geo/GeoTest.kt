package com.fablab503.velotrack.geo

import com.fablab503.velotrack.model.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    private val paris = LatLon(48.8566, 2.3522)
    private val london = LatLon(51.5074, -0.1278)

    @Test
    fun parisToLondonIsAbout343Km() {
        val d = Geo.distanceM(paris, london)
        assertEquals(343_500.0, d, 1_000.0)
    }

    @Test
    fun haversineIsSymmetricAndZeroForSamePoint() {
        assertEquals(0.0, Geo.haversineM(48.0, 17.0, 48.0, 17.0), 1e-9)
        val ab = Geo.haversineM(paris.lat, paris.lon, london.lat, london.lon)
        val ba = Geo.haversineM(london.lat, london.lon, paris.lat, paris.lon)
        assertEquals(ab, ba, 1e-6)
    }

    @Test
    fun bearingDueEastOnEquatorIs90() {
        val b = Geo.bearingDeg(LatLon(0.0, 0.0), LatLon(0.0, 1.0))
        assertEquals(90.0, b, 1e-6)
    }

    @Test
    fun bearingDueNorthIs0AndDueSouthIs180() {
        assertEquals(0.0, Geo.bearingDeg(LatLon(0.0, 0.0), LatLon(1.0, 0.0)), 1e-6)
        assertEquals(180.0, Geo.bearingDeg(LatLon(1.0, 0.0), LatLon(0.0, 0.0)), 1e-6)
    }

    @Test
    fun bearingDueWestIs270() {
        assertEquals(270.0, Geo.bearingDeg(LatLon(0.0, 1.0), LatLon(0.0, 0.0)), 1e-6)
    }

    @Test
    fun normalizeDegWrapsInto0To360() {
        assertEquals(0.0, Geo.normalizeDeg(0.0), 1e-9)
        assertEquals(0.0, Geo.normalizeDeg(360.0), 1e-9)
        assertEquals(350.0, Geo.normalizeDeg(-10.0), 1e-9)
        assertEquals(10.0, Geo.normalizeDeg(370.0), 1e-9)
        assertEquals(90.0, Geo.normalizeDeg(-990.0), 1e-9)
        val tiny = Geo.normalizeDeg(-1e-15)
        assertTrue(tiny >= 0.0 && tiny < 360.0)
    }

    @Test
    fun angleDiffIsSignedShortestPath() {
        assertEquals(10.0, Geo.angleDiffDeg(350.0, 0.0), 1e-9)
        assertEquals(-10.0, Geo.angleDiffDeg(0.0, 350.0), 1e-9)
        assertEquals(180.0, Geo.angleDiffDeg(0.0, 180.0), 1e-9)
        assertEquals(180.0, Geo.angleDiffDeg(180.0, 0.0), 1e-9)
        assertEquals(0.0, Geo.angleDiffDeg(45.0, 405.0), 1e-9)
        assertEquals(-90.0, Geo.angleDiffDeg(90.0, 0.0), 1e-9)
    }

    @Test
    fun pointToSegmentDistanceForPerpendicularOffset() {
        // Segment running east along the equator; point 0.001 deg north of its middle (~110.5 m).
        val a = LatLon(0.0, 0.0)
        val b = LatLon(0.0, 0.01)
        val p = LatLon(0.001, 0.005)
        assertEquals(110.54, Geo.pointToSegmentM(p, a, b), 0.5)
    }

    @Test
    fun pointToSegmentDistanceClampsToEndpoints() {
        val a = LatLon(0.0, 0.0)
        val b = LatLon(0.0, 0.01)
        // Beyond b along the line: distance is to b itself (0.01 deg lon ~ 1113.2 m).
        val beyond = LatLon(0.0, 0.02)
        assertEquals(1113.2, Geo.pointToSegmentM(beyond, a, b), 1.0)
        // Before a: distance is to a.
        val before = LatLon(0.0, -0.01)
        assertEquals(1113.2, Geo.pointToSegmentM(before, a, b), 1.0)
    }

    @Test
    fun pointOnSegmentHasZeroDistance() {
        val a = LatLon(48.0, 17.0)
        val b = LatLon(48.0, 17.01)
        assertEquals(0.0, Geo.pointToSegmentM(LatLon(48.0, 17.005), a, b), 1e-6)
    }

    @Test
    fun degenerateSegmentDistanceIsDistanceToPoint() {
        val a = LatLon(0.0, 0.0)
        val p = LatLon(0.001, 0.0)
        assertEquals(110.54, Geo.pointToSegmentM(p, a, a), 0.5)
    }

    @Test
    fun projectOnSegmentReturnsFractionAndPoint() {
        val a = LatLon(0.0, 0.0)
        val b = LatLon(0.0, 0.01)
        val (t, proj) = Geo.projectOnSegment(LatLon(0.001, 0.005), a, b)
        assertEquals(0.5, t, 1e-9)
        assertEquals(0.0, proj.lat, 1e-12)
        assertEquals(0.005, proj.lon, 1e-12)
    }

    @Test
    fun projectOnSegmentClampsFraction() {
        val a = LatLon(0.0, 0.0)
        val b = LatLon(0.0, 0.01)
        val (tBeyond, pBeyond) = Geo.projectOnSegment(LatLon(0.0, 0.05), a, b)
        assertEquals(1.0, tBeyond, 1e-12)
        assertEquals(b, pBeyond)
        val (tBefore, pBefore) = Geo.projectOnSegment(LatLon(0.0, -0.05), a, b)
        assertEquals(0.0, tBefore, 1e-12)
        assertEquals(a, pBefore)
    }

    @Test
    fun metersPerPixelMatchesWebMercatorFormula() {
        assertEquals(156543.03392, Geo.metersPerPixel(0.0, 0.0), 1e-6)
        assertEquals(156543.03392 / 65536.0, Geo.metersPerPixel(16.0, 0.0), 1e-9)
        // At 60 deg latitude cos = 0.5.
        assertEquals(156543.03392 / 2.0 / 65536.0, Geo.metersPerPixel(16.0, 60.0), 1e-9)
    }
}
