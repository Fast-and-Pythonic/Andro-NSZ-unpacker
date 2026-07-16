#ifndef CPU_AFFINITY_H
#define CPU_AFFINITY_H

#include <stdint.h>

/* Thread CPU affinity, used by the core-aware scheduler to pin a conversion to a
 * specific core cluster (big/little). All calls act on the CURRENT thread. */

/* Pin the current thread to the CPUs selected in `mask` (bit i => CPU i). The
 * thread's previous affinity is snapshotted so cpu_affinity_reset() can restore
 * it. Returns 0 on success, or -errno on failure (e.g. EPERM on kernels that
 * forbid it). On non-Linux hosts it is a no-op returning 0. */
int cpu_affinity_set(uint64_t mask);

/* Restore the affinity that was in effect before the last cpu_affinity_set() on
 * this thread. Returns 0 on success (or if nothing was saved), or -errno. */
int cpu_affinity_reset(void);

#endif /* CPU_AFFINITY_H */
