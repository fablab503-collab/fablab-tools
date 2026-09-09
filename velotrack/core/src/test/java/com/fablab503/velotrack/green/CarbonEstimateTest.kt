package com.fablab503.velotrack.green

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These pin the published constants as much as the arithmetic. If someone later "rounds" the car
 * factor or quietly swaps a tailpipe figure for the well-to-wheel one, a rider's number moves and
 * the citation on screen stops matching what is displayed. That is the failure worth catching.
 */
class CarbonEstimateTest {

    @Test
    fun `ten kilometres by car is about two kilograms`() {
        // 10 km x 209.9 g = 2099 g
        assertEquals(2.099, CarbonEstimate.carEquivalentKg(10_000.0), 0.001)
    }

    @Test
    fun `the car factor is the well-to-wheel one, not the tailpipe one`() {
        // Tailpipe alone is 165.91 g/km; using it would understate a car by 21%.
        assertEquals(209.9, CarbonEstimate.CAR_G_PER_KM, 0.0001)
        assertTrue(
            "the factor must include upstream fuel production",
            CarbonEstimate.CAR_G_PER_KM > 165.91,
        )
    }

    @Test
    fun `a bicycle is never free`() {
        assertTrue(CarbonEstimate.bikeFootprintKg(10_000.0) > 0.0)
        assertEquals(0.051, CarbonEstimate.bikeFootprintKg(10_000.0), 0.0001)
    }

    @Test
    fun `counting food roughly quadruples the bicycle's own footprint`() {
        val without = CarbonEstimate.bikeFootprintKg(10_000.0, includeFood = false)
        val with = CarbonEstimate.bikeFootprintKg(10_000.0, includeFood = true)
        assertEquals(0.21, with, 0.0001)
        assertTrue(with > without * 3)
    }

    @Test
    fun `a ride that replaced nothing avoids nothing`() {
        // The honest default for recreation: the comparison still shows, the credit does not.
        assertEquals(0.0, CarbonEstimate.avoidedKg(10_000.0, substitution = 0.0), 0.0001)
        assertTrue(CarbonEstimate.carEquivalentKg(10_000.0) > 0.0)
    }

    @Test
    fun `a ride that fully replaced a drive avoids the car minus the bicycle`() {
        val expected = 2.099 - 0.051
        assertEquals(expected, CarbonEstimate.avoidedKg(10_000.0, substitution = 1.0), 0.001)
    }

    @Test
    fun `the commute substitution rate is the published meta-analysis figure`() {
        assertEquals(0.24, CarbonEstimate.COMMUTE_SUBSTITUTION, 0.0001)
        val quarter = CarbonEstimate.avoidedKg(10_000.0, CarbonEstimate.COMMUTE_SUBSTITUTION)
        assertEquals((2.099 - 0.051) * 0.24, quarter, 0.001)
    }

    @Test
    fun `substitution outside zero to one is clamped rather than trusted`() {
        val full = CarbonEstimate.avoidedKg(10_000.0, 1.0)
        assertEquals(full, CarbonEstimate.avoidedKg(10_000.0, 4.0), 0.0001)
        assertEquals(0.0, CarbonEstimate.avoidedKg(10_000.0, -2.0), 0.0001)
    }

    @Test
    fun `a negative distance cannot mint negative emissions`() {
        assertEquals(0.0, CarbonEstimate.carEquivalentKg(-5000.0), 0.0001)
        assertEquals(0.0, CarbonEstimate.bikeFootprintKg(-5000.0), 0.0001)
    }

    @Test
    fun `petrol and phone equivalences use the EPA factors`() {
        // 2.099 kg / 2.348 kg per litre
        assertEquals(0.894, CarbonEstimate.petrolLitres(10_000.0), 0.01)
        // 1 kg / 12.4 g
        assertEquals(80.6, CarbonEstimate.phoneCharges(1.0), 0.1)
    }

    @Test
    fun `a hundred kilometres is a number a rider can sanity check`() {
        // Roughly 21 kg for 100 km, i.e. about 9 litres of petrol - close to a real car's tank use.
        assertEquals(20.99, CarbonEstimate.carEquivalentKg(100_000.0), 0.01)
        assertEquals(8.94, CarbonEstimate.petrolLitres(100_000.0), 0.05)
    }
}
