package com.aatorque.stats

/** Exactly one icon in the existing header slot. */
object HeaderStatus {
    enum class Icon { NONE, BROKEN_LINK, ENGINE_OFF, ENGINE_UNKNOWN, APL }
    fun select(broken: Boolean, engine: EngineState.State, aplEnabled: Boolean): Icon = when {
        broken -> Icon.BROKEN_LINK
        engine == EngineState.State.OFF -> Icon.ENGINE_OFF
        engine == EngineState.State.UNKNOWN -> Icon.ENGINE_UNKNOWN
        engine == EngineState.State.RUNNING && aplEnabled -> Icon.APL
        engine == EngineState.State.DISCONNECTED && aplEnabled -> Icon.APL
        else -> Icon.NONE
    }
}
