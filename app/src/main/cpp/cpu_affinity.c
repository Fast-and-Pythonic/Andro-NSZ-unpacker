#define _GNU_SOURCE
#include "cpu_affinity.h"

#if defined(__linux__)

#include <sched.h>
#include <errno.h>

/* Per-thread snapshot of the affinity in effect before cpu_affinity_set(), so
 * reset() restores the real prior mask rather than a synthetic "all CPUs" (the
 * process may already be constrained by the system). */
static __thread cpu_set_t g_prev;
static __thread int       g_have_prev = 0;

int cpu_affinity_set(uint64_t mask)
{
   cpu_set_t set;
   CPU_ZERO(&set);
   for (int i = 0; i < 64 && i < CPU_SETSIZE; i++) {
      if (mask & ((uint64_t)1 << i)) {
         CPU_SET(i, &set);
      }
   }
   if (CPU_COUNT(&set) == 0) {
      return -EINVAL;
   }

   CPU_ZERO(&g_prev);
   g_have_prev = (sched_getaffinity(0, sizeof(g_prev), &g_prev) == 0) ? 1 : 0;

   if (sched_setaffinity(0, sizeof(set), &set) != 0) {
      return -errno;
   }
   return 0;
}

int cpu_affinity_reset(void)
{
   if (!g_have_prev) {
      return 0;
   }
   g_have_prev = 0;
   if (sched_setaffinity(0, sizeof(g_prev), &g_prev) != 0) {
      return -errno;
   }
   return 0;
}

#else /* non-Linux host build (CLI/tests) — affinity is a no-op */

int cpu_affinity_set(uint64_t mask) { (void)mask; return 0; }
int cpu_affinity_reset(void)        { return 0; }

#endif
