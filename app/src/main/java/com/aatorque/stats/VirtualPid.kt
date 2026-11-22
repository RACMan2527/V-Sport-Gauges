package com.aatorque.stats

/**
 * App-owned daily metrics. These identifiers are never sent to Torque or the ECU.
 * Values are supplied by the existing FuelMonitor sampler.
 */
object VirtualPid {
    const val PREFIX = "vs_"
    data class Definition(val id: String, val name: String, val unit: String, val maximum: Int)
    val definitions = listOf(
        Definition("VS_DAILY_DISTANCE", "Daily Distance", "km", 1000),
        Definition("VS_DAILY_TIME", "Daily Drive Time", "s", 86400),
        Definition("VS_DAILY_MPG", "Daily MPG", "MPG", 100),
        Definition("VS_DAILY_GALLONS", "Daily Fuel Used", "gal", 100),
        Definition("VS_TRIP3_DISTANCE", "Trip 3 Distance", "km", 1000000),
        Definition("VS_TRIP3_TIME", "Trip 3 Drive Time", "s", 100000000),
        Definition("VS_TRIP3_MPG", "Trip 3 MPG", "MPG", 100),
        Definition("VS_TRIP3_GALLONS", "Trip 3 Fuel Used", "gal", 100000)
    )
    fun find(selection: String): Definition? =
        definitions.firstOrNull { selection == PREFIX + it.id }
}
