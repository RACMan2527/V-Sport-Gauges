package com.aatorque.stats

/**
 * App-owned daily metrics. These identifiers are never sent to Torque or the ECU.
 * Values are supplied by the existing FuelMonitor sampler.
 */
object VirtualPid {
    const val PREFIX = "vs_"
    data class Definition(val id: String, val name: String, val unit: String, val maximum: Int)
    val definitions = listOf(
        Definition("VS_DAILY_DISTANCE", "Daily Distance", "mi", 500),
        Definition("VS_DAILY_TIME", "Daily Drive Time", "min", 1440),
        Definition("VS_DAILY_MPG", "Daily MPG", "MPG", 100),
        Definition("VS_DAILY_GALLONS", "Daily Fuel Used", "gal", 100)
    )
    fun find(selection: String): Definition? =
        definitions.firstOrNull { selection == PREFIX + it.id }
}
