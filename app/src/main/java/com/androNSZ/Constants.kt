package com.androNSZ

/**
 * Global constants for AndroNSZ application
 */
object Constants {
    /**
     * Progress bar update interval (in milliseconds)
     * More frequent updates for smooth animation
     * Value 250ms = 4 updates per second
     */
    const val PROGRESS_BAR_UPDATE_INTERVAL_MS = 100L
    
    /**
     * Numeric indicators update interval (in milliseconds)
     * Less frequent updates for better readability of percentages, speed and size
     * Value 500ms = 2 updates per second
     */
    const val PROGRESS_NUMERIC_UPDATE_INTERVAL_MS = 500L
}
