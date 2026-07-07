# Architecture decisions

Non-trivial technical decisions. Format: `A##` — a stable anchor for links.
Most of A01–A07 are parts of one performance story: unpacking 2 GB was sped up
from ~50 s to ~7 s (parity with the desktop reference), and on 9 GB it beats the
competitor. Verification is essentially free, CNMT-based and non-fatal — see A12.

## A01: zstd is built with `-O3` even in debug
**Context:** the zstd dependency in debug inherits `CMAKE_BUILD_TYPE=Debug` → `-O0`
and `-DDEBUGLEVEL=1` (internal asserts). Decompression is the dominant cost of the
hot path, and `-O0` tanks it.
**Decision:** in `CMakeLists.txt`, force `-O3 -DNDEBUG -UDEBUGLEVEL -DDEBUGLEVEL=0`
for the `libzstd_static` target, regardless of build type.
**Consequences:** the debug APK unpacks at release speed. This turned out to be the
decisive win (see also [gotchas.md](gotchas.md) G02).

## A02: Hardware AES-CTR (ARMv8 crypto) + software fallback
**Context:** AES-CTR decrypts NCA sections (crypto_type 3/4) on every block.
**Alternatives:** scalar FIPS-197 only (slow); hardware only (crashes on CPUs
without the extension).
**Decision:** `aes_ctr.c` has an intrinsics path (`vaeseq_u8/vaesmcq_u8`, processing
4 CTR blocks at a time) and a portable scalar fallback. ARM64 is built with
`-march=armv8-a+crypto`.
**Consequences:** on modern phones crypto is nearly free; on old ones it runs slower
but correctly.

## A03: Hardware SHA-256 (ARMv8 crypto)
**Context:** SHA-256 is needed both for verification and for matching filenames by
hex prefix; it is computed on the fly over the output stream.
**Decision:** `sha256.c` uses `vsha256hq_u32` etc. intrinsics with the software API
as a fallback.
**Consequences:** verifying a finished NSP costs almost no time, so it runs whenever
header_key is available.

## A04: Async writer (background write + hashing thread)
**Context:** the producer (decompress+decrypt) and the disk compete for time.
**Decision:** `async_writer.c` — a background thread that receives ready chunks via a
buffer queue; it `fwrite`s them and feeds the SHA-256 context while the producer
prepares the next chunk. Writes are strictly in submit order → output is sequential
and SHA sees bytes in order. API: `aw_start/aw_get_buffer/aw_submit/aw_finish`.
**Consequences:** CPU work and I/O overlap. The price is double buffering of memory.

## A05: No-copy input via `fd:N`, output via `/proc/self/fd`
**Context:** SAF/scoped storage gives a `Uri`, but native needs a path/descriptor.
Copying a multi-gigabyte input to a temp file is expensive.
**Decision:** input — `context.contentResolver.openFileDescriptor(uri,"r")`; if the fd
is seekable (`statSize >= 0`), native gets the string `"fd:N"` and does
`dup()+fdopen()`. Output — MediaStore Downloads + `"/proc/self/fd/<fd>"`.
**Consequences:** no extra temp copy. But some FUSE providers hand out a descriptor
native cannot re-open → a fallback is needed (see A06 / G03).

## A06: Fallback to a temp copy on direct-read failure
**Context:** direct fd reading sometimes fails while parsing PFS0 on FUSE storage.
**Decision:** if `nativeConvert` returned `ERR_OPEN_INPUT/INVALID_PFS0/INVALID_NCZ/
IO` with an active `inputPfd`, `NszConverter` copies the input to cache
(`resolveToFilePath`) and retries. The fast attempt fails immediately at parsing, so
the retry is almost free.
**Consequences:** reliability across all providers at the cost of a rare retry.

## A07: Core-adaptive batch parallelism (queue mode)
**Context:** in multi-file mode files can be converted in parallel.
**Decision:** `BATCH_CONCURRENCY = (availableProcessors()/2 - 1)`, clamped to `1..3`
(8 cores → 3, 6 → 2, ≤4 → 1). Files are launched via a `Semaphore`, native work runs
on `Dispatchers.IO`, state is written on Main.
**Consequences:** loads several cores. The cap of 3 — we hit the storage write ceiling.
Per-file progress is in `activeFileProgress` (keyed by index).
**Override (experiment):** `MainViewModel.resolveConcurrency(verificationEnabled, override)`
keeps this auto value as the default, but when verification is **off** an optional
Settings slider (`decompression_threads`, 0 = auto) raises the worker count up to the
full core count. The gate to verify-off scopes the "use all cores" experiment and keeps
the safe 1..3 whenever CNMT verification runs. Purpose: measure whether decompression
actually scales past 3 workers or plateaus at the write ceiling — this decides the next
step (intra-file `block-parallel-wip` vs. the A12 §3 "SHA on dedicated cores" layout).
NB: parallelism is per-file, so the slider only helps a queue/folder of ≥ N files, not a
single large file.
**Measured (2026-07-07):** decompression is **CPU-bound**, not write-bound as this note
originally assumed — 1 worker ~250–360 MB/s, scaling to ~800 MB/s aggregate at ~6 cores
(past 6 is uncertain). So the old "storage write ceiling" only bounds the *auto* value,
not the achievable throughput; the override exists to exploit that.
**Load distribution (LPT):** when smart distribution is on (`smart_distribution`,
default ON), both batch and folder dispatch the **largest files first**. The existing
`Semaphore` is fair (FIFO), so creating the coroutines in descending-size order makes
permits fall to the largest files first — a heavy file never trails the batch on a slow
core, and small files fill the tail. Ordered by *input* (compressed) size (a proxy for
the unpacked size, unknown until parsing). `FolderProcessor.processFolder` takes a
`largestFirst` flag. Explicit big/little core affinity (`sched_setaffinity` + sysfs
topology) is the planned next layer under the same toggle — [status.md](status.md).

## A08: Per-app language via `attachBaseContext` + SharedPreferences
**Context:** changing the language in-app without changing the system one.
**Decision:** `MainActivity.attachBaseContext()` synchronously reads the language and
wraps the context with the right `Locale` before UI inflation. That's why `language`
is stored in **SharedPreferences** (synchronous), not DataStore — coroutines aren't
available there yet. Changing the language calls `Activity.recreate()`.
**Consequences:** instant language application; this one setting lives apart from the
others (which are in the DataStore flow).

## A09: Per-type progress throttling (live bar, each number at its own pace)
**Context:** frequent updates are needed for a smooth bar, but they jitter the numbers
(percent/speed/size), which become hard to read. There used to be one shared "number"
constant, but percent and size were actually derived from live `done`/`total` and
flickered at the bar's rate — only speed was really throttled.
**Decision:** four independent constants in `Constants.kt` — `PROGRESS_BAR_…` (smooth
animation, follows the live byte counter), `PROGRESS_PERCENT_…`, `PROGRESS_SPEED_…`,
`PROGRESS_SIZE_…` (defaults 100 / 250 / 250 / 250 ms). The logic lives in
`util/ProgressThrottler.kt` (one instance per progress stream): `sample(done,total)`
returns `null` until the bar interval elapses, otherwise a `ConversionProgress` where
the live `doneBytes/totalBytes` drive the bar while the `display*` fields
(`displayPercent`, `displayDoneBytes/TotalBytes`, `speedMBps`) are each "frozen" at
their own interval. `ConversionProgress.display*` default from the live values, so
non-throttling callers (folder current-file, final pin) don't break. Used at four
emit sites: `NszConverter` (fd and temp paths), `MainViewModel` (batch overall),
`FolderProcessor`. The UI (`SingleFilesUI`, `FolderModeUI`) draws the bar from the
live `.percent` and the text from `display*`.
**Consequences:** a smooth bar + calm, per-type tunable numbers. To change one
metric's pace — edit one constant. Old downside: throttling can leave the overall bar
a hair below 100% at the end, so batch has a final "top-up" emit (see
[gotchas.md](gotchas.md) G01).

## A10: ThinLTO for the native engine
**Context:** the engine is split into ~11 translation units; without LTO the compiler
won't inline hot helpers across file boundaries (the AES step into the decompression
loop, `sha256_update` into the verifier).
**Decision:** `-flto=thin` on both compile and link. ThinLTO is parallel/incremental,
so the build cost is small.
**Consequences:** a modest win (the hot path is already in hardware crypto and zstd),
low risk. LTO must be passed to the linker too, or the bitcode objects won't be
codegen'd.

## A11: XCI output mirrors `XciStream` (0x8000 HFS0 alignment, hfs0 at 0xF000)
**Context:** the XCZ→XCI path must produce the byte-for-byte same `.xci` as reference
nsz (as already achieved for NSP). We used to write compact HFS0 headers and copy the
`0x200..hfs0_offset` region, which made the container layout differ from the reference.
**Decision:** reproduce `nsz.Fs.Xci.XciStream` / `Hfs0Stream` exactly:
- Each HFS0 partition (root and nested) reserves a fixed header
  `HFS0_PARTITION_HEADER = 0x8000`; the first file's data starts on that boundary. The
  gap is encoded in the entry offsets (`entry.offset = 0x8000 − header_size`) and
  padded with zeros. The string table is **raw, without 0x20 padding**
  (`string_table_size` = raw length). See [hfs0.c](../app/src/main/cpp/hfs0.c)
  `hfs0_write_header`, `hfs0_computed_header_size` (→ 0x8000).
- In [ncz_engine.c](../app/src/main/cpp/ncz_engine.c) the output: the first `0x200` of
  the input verbatim, then **zeros** up to `XCI_ROOT_HFS0_OFFSET = 0xF000`, then the
  root HFS0 at 0xF000. That's exactly what `XciStream` does (seek 0xF000, the region is
  a hole/zeros). Zeros are written explicitly (not via seek) for non-seekable output fds.
- SHA256/`hashed_region_size` in HFS0 entries stay zero (as before — matches the
  reference).
**Consequences:** `.xci` matches the reference for ordinary (trimmed) XCI. The
`0x200..0xF000` region (gamecard cert) is zeroed — but in an nsz-produced `.xcz` it is
already zero (the compressor uses the same `XciStream`), so nothing is lost. Full XCI
see [gotchas.md](gotchas.md) G06.

## A12: CNMT-based verification (non-fatal), with a filename fallback and a toggle
**Context:** the engine used to compare only the first 16 bytes of a decompressed NCA's
SHA-256 against the content-id in the filename. But the content-id is only **half** the
NCA hash, so it is **not authoritative**. Reference nsz instead reads the **full** hash
of every content NCA from the CNMT (`FileExistingChecks.ExtractHashes` →
`Cnmt.contentEntries[].hash`) and checks each unpacked NCA's full SHA-256 for set
membership.
**Decision:** port the CNMT approach. Before per-file processing, the engine scans the
**input** container for META NCAs (`*.cnmt.nca`, stored uncompressed but encrypted),
decrypts them and collects the expected hashes into a `CnmtHashSet`
([nca_cnmt.c](../app/src/main/cpp/nca_cnmt.c)). During conversion each NCA's full
SHA-256 (already computed streaming, essentially free) is checked against the set. The
CNMT NCA itself is excluded from the set (it never lists itself). XCZ builds one set
**per HFS0 partition** (the secure partition carries the META).
**Reading the CNMT** reuses existing crypto: AES-XTS header decrypt with `header_key`
(as in `nca_verifier.c`), then AES-128-**ECB** unwrap of the key area with
`key_area_key_application_XX` read **directly** from prod.keys (XX = key generation =
`max(cryptoType, cryptoType2) − 1`; no master-key/KEK derivation, unlike the reference),
then AES-CTR of the PFS0 section (key = key-area entry index 2, counter = section nonce
reversed). `nca_cnmt.c` carries its own one-block AES-ECB core because the primitives in
`aes_xts.c` are `static` (and that file must not be edited).
**Fallback ladder:** CNMT → filename → off. If keys are missing or the META NCA can't be
parsed, the engine falls back to the legacy content-id check and marks it `VERIFIED …
(by name)`. A settings toggle (`should_hash`) can disable hashing entirely — the point
of the toggle, since that is what the SHA cost buys. Default **ON**: CNMT verification is
now authoritative *and* nearly free, so there's no reason to ship it off (this supersedes
the roadmap's earlier "probably off").
**Semantics:** a mismatch stays **non-fatal** — `WARN` "hash mismatch (output kept)", the
file is never deleted. Config is pushed once per batch via
`nativeSetVerification(enabled, header_key, key_area_keys)` and is read-only during
conversion (safe under `BATCH_CONCURRENCY` — see [gotchas.md](gotchas.md)). The
structural `nca_verify_nsp` post-pass (section-header hashes) is kept, orthogonal.
**Deferred:** per-core layout optimization (all SHA on 1–2 cores) — [status.md](status.md).

## A13: Core-aware scheduler for heterogeneous CPUs (big.LITTLE)
**Context:** measurement (2026-07-07) showed decompression is CPU-bound and scales to
~6 cores (A07). But plain largest-first ordering over a work-conserving `Semaphore`
(step 1) actually **regressed** (~840→~700 MB/s): order alone doesn't control *which*
core takes *which* file, so a heavy file can land on a slow core and become the tail.
Assigning files to cores of different speeds to minimize the finish time of the last
one is the **Q||Cmax** problem.
**Decision:** a core-aware scheduler ([CoreScheduler.kt](../app/src/main/java/com/androNSZ/fs/CoreScheduler.kt)),
gated by the `smart_distribution` toggle.
- **Topology** ([CpuTopology.kt](../app/src/main/java/com/androNSZ/util/CpuTopology.kt)):
  per-core speed from sysfs `cpu_capacity` (→ `cpufreq/cpuinfo_max_freq` → homogeneous),
  and a per-tier **cluster** affinity mask.
- **Assignment** = LPT (largest file first) + greedy earliest-completion-time
  (`load[c] + size/speed[c]`), then a **makespan-minimizing local search** (move a job
  off the max-load core while it strictly lowers the global max). Work proxy = input
  (compressed) size; speed proxy = capacity. Static (no work-stealing): a slow core may
  idle rather than take a heavy file — protects makespan.
- **Execution:** one coroutine per used core, each processing its assigned files
  sequentially and **pinning** the native decompress to its cluster via
  `nativeSetThreadAffinity` (new `cpu_affinity.c`, `sched_setaffinity` on the IO thread
  before `nativeConvert`; the `async_writer` pthread inherits the mask). Reset after.
- **Fallback:** only engages when the CPU is heterogeneous *and* affinity works
  (probed once; EPERM on some OEM kernels). Otherwise → the natural-order `Semaphore`
  baseline (order-only would just re-introduce the regression). Homogeneous CPU → baseline.
**Consequences:** heavy files run on fast cores, pinned, without stranding on slow cores.
`FolderProcessor.processFolder` gained a `smartDistribution` param; batch/folder share a
per-file `body(index, mask)`. **Caveats:** static proxies don't see thermal throttling
(pinning heavy work to big cores can throttle them) and compressed size is a proxy for
unpacked size — calibration + guarded work-stealing (hybrid) is the deferred next step
([status.md](status.md)). Then the SHA-256 per-core layout (A12 §3).
