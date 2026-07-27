package com.androNSZ.util

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The gate that decides how many files convert at once, shared by queue mode
 * ([com.androNSZ.viewmodel.MainViewModel]) and folder/combined mode
 * ([com.androNSZ.fs.FolderProcessor]) — both used to spell out the same
 * `Semaphore(concurrency)` pattern separately.
 *
 * [onChange] is invoked whenever a worker starts or finishes, with the current
 * (target, active) pair; it feeds [ThroughputRecorder], which needs both to tell a
 * fully loaded stretch from a starved one.
 *
 * The pool used to be resizable — a `Semaphore` cannot be resized, so it was created
 * wide with the surplus held back as ballast permits, and shrinking waited for a file
 * to finish rather than aborting one. That existed for the in-job search, which was
 * removed (architecture.md A07); the count is now fixed for a job's lifetime and the
 * machinery went with its only caller.
 */
class WorkerPool(
   workers: Int,
   private val onChange: ((target: Int, active: Int) -> Unit)? = null
) {

   /** Workers allowed to run at once. Fixed for the life of the pool. */
   val target: Int = workers.coerceAtLeast(1)

   private val sem = Semaphore(target)
   private val _active = AtomicInteger(0)

   /** Workers actually converting right now — never more than [target]. */
   val active: Int get() = _active.get()

   /** Runs [block] as one worker, waiting for a slot first. */
   suspend fun <T> withWorker(block: suspend () -> T): T = sem.withPermit {
      _active.incrementAndGet()
      notifyChange()
      try {
         block()
      } finally {
         _active.decrementAndGet()
         notifyChange()
      }
   }

   private fun notifyChange() {
      onChange?.invoke(target, _active.get())
   }
}
