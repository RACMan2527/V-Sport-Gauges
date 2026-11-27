package com.aatorque.stats

/** Fixed APL requirements; dashboard changes never change these identities. */
object AplPidInventory {
    data class Requirement(val key: String, val id: String, val percentMetadata: Boolean = false)
    val requirements = listOf(
        Requirement("horsepower", "e17181,-2138499810"),
        Requirement("torque", "e6d079,1880094478"),
        Requirement("rpm", "0c,0"),
        Requirement("gps_speed", "ff1001,0"),
        Requirement("gear", "22199a,65"),
        Requirement("pedal", "49,0", true),
        Requirement("throttle", "11,0", true),
        Requirement("engine_load", "04,0"),
        Requirement("timing_advance", "0e,0"),
        Requirement("oil_pressure", "221c1b,-318725782")
    )
    val ids = requirements.map { it.id }.toSet()
    const val RPM = "0c,0"

    /** Standard Mode 01 pedal D and absolute throttle are candidates until Torque
     * advertises matching percent metadata. Never silently substitute another PID.
     * Call off the UI/polling thread once per APL connection. */
    fun verify(ids: List<String>, info: List<String>): Map<String, String> {
        val metadata = ids.zip(info).toMap()
        return requirements.associate { requirement ->
            val fields = metadata[requirement.id]?.split(",")
            val description = fields?.take(2)?.joinToString(" ")?.lowercase(java.util.Locale.US).orEmpty()
            val unit = fields?.getOrNull(2)?.trim().orEmpty()
            val status = when {
                fields == null -> "missing"
                requirement.percentMetadata && unit != "%" -> "unexpected_unit"
                requirement.key == "pedal" && !description.contains("pedal") -> "unexpected_description"
                requirement.key == "throttle" && !description.contains("throttle") -> "unexpected_description"
                else -> "metadata_present"
            }
            requirement.key to status
        }
    }
}
