package com.fablab503.velotrack.green

/**
 * What riding instead of driving is worth, in CO2e.
 *
 * Every constant here is a published figure with a source and a year, because a number a rider
 * screenshots and repeats has to survive being checked. Nothing is averaged across sources that
 * disagree; where they disagree, both are offered and labelled.
 *
 * **The honest framing, and why it is not "you saved X".** Cycling does not remove CO2 from the
 * air. It avoids emissions that would otherwise have happened, and only when the ride actually
 * replaces a car journey. Nobody has published a substitution rate for recreational riding, and a
 * rider who drives to the trailhead may replace nothing at all. So the default presentation is a
 * plain comparison - *an average car would have emitted this much over the same distance* - which
 * is a fact about the car, not a claim about the rider. Attributing it as a personal saving needs
 * the rider to say the ride replaced a drive; see [avoided].
 *
 * This also keeps the app clear of Directive (EU) 2024/825, which from 27 September 2026 bans
 * "carbon neutral", "climate compensated" and "reduced climate impact" claims outright, with no
 * substantiation defence.
 */
object CarbonEstimate {

    /**
     * Average car, fuel unknown, **well-to-wheel**: 209.9 g CO2e per vehicle-kilometre.
     *
     * UK DESNZ *Greenhouse gas reporting: conversion factors 2026* - 165.91 g tailpipe plus
     * 43.99 g upstream (extraction, refining, distribution). The upstream fifth is what gets
     * dropped when people quote "tailpipe only", and leaving it out understates a car by 22%.
     *
     * Per *vehicle*-km on purpose. A rider who does not drive removes a whole car from the road,
     * not the 1/1.6 of one that a per-passenger figure would credit them with.
     */
    const val CAR_G_PER_KM = 209.9

    /** The EU comparison, for reference: EEA / CE Delft 2020 (2018 data), 143 g per passenger-km. */
    const val CAR_EU_G_PER_PASSENGER_KM = 143.0

    /**
     * A bicycle is not zero: 5.1 g CO2e per km for building and maintaining it, amortised.
     *
     * Brand et al. (2021), *Transportation Research Part D* 93:102764. Food is deliberately
     * excluded - the authors judge the evidence weak that everyday cycling raises overall dietary
     * intake. [BIKE_G_PER_KM_WITH_FOOD] is the other stance.
     */
    const val BIKE_G_PER_KM = 5.1

    /**
     * The same bicycle counting the extra food a rider metabolises: 21 g/km (5 build + 16 food).
     * European Cyclists' Federation (2011). Offered because the food term is genuinely contested,
     * not because it is more correct.
     */
    const val BIKE_G_PER_KM_WITH_FOOD = 21.0

    /**
     * Share of cycled kilometres that actually replace a car, when a ride is a real journey:
     * 0.24. Bigazzi & Wong (2020), *Transportation Research Part D* 85:102412, meta-analysis of
     * 24 studies (car 24%, bike 27%, transit 33%, walk 10%, induced 1%).
     *
     * There is no published figure for recreational riding, which is why it is not the default.
     */
    const val COMMUTE_SUBSTITUTION = 0.24

    /** Petrol: 2.348 kg CO2 per litre burned. US EPA Greenhouse Gas Equivalencies (2022 data). */
    const val PETROL_KG_PER_LITRE = 2.348

    /** One smartphone charged: 12.4 g CO2e. US EPA Greenhouse Gas Equivalencies (2022 data). */
    const val PHONE_CHARGE_G = 12.4

    /**
     * What an average car would have emitted over [distanceM], in kilograms of CO2e.
     *
     * This is the headline figure and it is a statement about cars, not about the rider. It makes
     * no assumption that the ride replaced a drive.
     */
    fun carEquivalentKg(distanceM: Double): Double =
        (distanceM.coerceAtLeast(0.0) / 1000.0) * CAR_G_PER_KM / 1000.0

    /** The bicycle's own footprint over the same distance, in kg CO2e. Never zero. */
    fun bikeFootprintKg(distanceM: Double, includeFood: Boolean = false): Double {
        val g = if (includeFood) BIKE_G_PER_KM_WITH_FOOD else BIKE_G_PER_KM
        return (distanceM.coerceAtLeast(0.0) / 1000.0) * g / 1000.0
    }

    /**
     * Emissions genuinely avoided, in kg CO2e, given how much of the distance replaced a car.
     *
     * [substitution] is the fraction of these kilometres that would otherwise have been driven:
     * 0 for a ride that replaced nothing (the honest default for recreation), up to 1 for a
     * journey that would certainly have been made by car. The bicycle's own footprint is
     * subtracted only over the substituted share, since the rest was going to be ridden anyway.
     */
    fun avoidedKg(distanceM: Double, substitution: Double, includeFood: Boolean = false): Double {
        val s = substitution.coerceIn(0.0, 1.0)
        return (carEquivalentKg(distanceM) - bikeFootprintKg(distanceM, includeFood)) * s
    }

    /** Litres of petrol an average car would have burned over [distanceM]. */
    fun petrolLitres(distanceM: Double): Double = carEquivalentKg(distanceM) / PETROL_KG_PER_LITRE

    /** Smartphone charges of the same mass of CO2e - a scale people have a feel for. */
    fun phoneCharges(kg: Double): Double = kg * 1000.0 / PHONE_CHARGE_G
}
