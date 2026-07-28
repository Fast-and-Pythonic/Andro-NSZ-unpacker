package com.androNSZ

/**
 * Global constants for AndroNSZ application
 */
object Constants {
    /**
     * Progress bar refresh interval (in milliseconds).
     * Drives only the smooth animation of the bar itself, which follows the live
     * byte counter. Keep this short.
     * Value 100ms = 10 updates per second.
     */
    const val PROGRESS_BAR_UPDATE_INTERVAL_MS = 100L

    /**
     * Percentage readout refresh interval (in milliseconds).
     * The "%" text is frozen between ticks so the number stays readable while the
     * bar keeps animating.
     * Value 250ms = 4 updates per second.
     */
    const val PROGRESS_PERCENT_UPDATE_INTERVAL_MS = 250L

    /**
     * Speed readout refresh interval (in milliseconds).
     * Also the window over which MB/s is averaged: a longer interval yields a
     * steadier, less jumpy speed.
     * Value 250ms = 4 updates per second.
     */
    const val PROGRESS_SPEED_UPDATE_INTERVAL_MS = 250L

    /**
     * Size readout ("X / Y") refresh interval (in milliseconds).
     * Value 250ms = 4 updates per second.
     */
    const val PROGRESS_SIZE_UPDATE_INTERVAL_MS = 250L

    /**
     * How often the speed-test card refreshes (in milliseconds).
     * A display cadence only — the test's own verdict is timed end to end and does
     * not sample anything at this or any other interval (see util/RealFileBenchmark).
     * Value 200ms = 5 updates per second.
     */
    const val BENCH_TICK_INTERVAL_MS = 200L
}
