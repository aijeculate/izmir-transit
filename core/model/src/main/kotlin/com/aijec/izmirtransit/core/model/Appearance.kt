package com.aijec.izmirtransit.core.model

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** The look of panels and controls: calm opaque cards, or iOS-style liquid glass. */
enum class UiStyle { CALM, LIQUID_GLASS }

/** Accent palettes; the canvas and panels stay the same, so every theme stays calm. */
enum class AccentTheme { OCEAN, TEAL, VIOLET, CORAL, GRAPHITE }

/** What the map draws; each can be switched off from the map's layer button. */
enum class MapLayer {
    BUS_LINES,
    METRO,
    TRAM,
    IZBAN,
    FERRY,
    /** Stop badges for everything in view when zoomed in. */
    STOPS;

    companion object {
        /** The layer that shows a mode's routes and stops. */
        fun forMode(mode: TransitMode): MapLayer = when (mode) {
            TransitMode.BUS -> BUS_LINES
            TransitMode.METRO -> METRO
            TransitMode.TRAM -> TRAM
            TransitMode.IZBAN -> IZBAN
            TransitMode.FERRY -> FERRY
        }
    }
}
