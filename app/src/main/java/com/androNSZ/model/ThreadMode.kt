package com.androNSZ.model

/**
 * Where the number of files unpacked in parallel comes from.
 *
 * The count used to be a single hardcoded value, measured on one phone (SM8735 +
 * UFS). Unpacking is bounded by the *storage write* path (architecture.md A15), so
 * that number is a property of the device, not of the app: a budget eMMC can plateau
 * at 2 threads while UFS 4.0 keeps scaling to 6–8. Hence an explicit source instead
 * of one hidden constant.
 *
 * - [MANUAL] — the slider on the thread-count screen decides.
 * - [HALF] — half the CPU cores. **The default**: it needs no measurement, it is
 *   never far wrong on either kind of storage, and it leaves the rest of the device
 *   responsive during a long job.
 * - [CALIBRATED] — a value found by the real-file test; until that test has been run
 *   it falls back to [HALF].
 *
 * An `ADAPTIVE` mode existed until 2026-07-27: it searched for the best count while a
 * real job ran. It needed tens of GB of output before it could reach a verdict, so on
 * ordinary jobs it only ever reported "not enough data" — deleted along with
 * `AdaptiveWorkerSearch`. A stored `ADAPTIVE` migrates to [HALF].
 */
enum class ThreadMode {
   MANUAL,
   HALF,
   CALIBRATED
}
