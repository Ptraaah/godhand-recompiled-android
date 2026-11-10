# CALL-COUNT step 0 — measurement only: the mergeable unit is the in-job segment train, not the same-entry pair; gate GO for a train design with a light re-entry, with three instrumentation prerequisites inside step 1

**Scope: evidence and a gate verdict only. No code was changed, nothing
was built. Sources: M37-LOG-ANALYSIS.md (verdict PARTIAL, residency
tuning closed), M36-LOG-ANALYSIS.md §6–§7, M35-LOG-ANALYSIS.md device
gates, PATH-VUJOIN.md (join mechanism map), the shipped m37 core as
`resident2-build-work/orig/ps2_vu1_core.cpp` + `resident2.patch`, the
callback layer in `rider-build-work/orig/ps2_runtime.cpp`, the VIF1
interpreter in `PS2Recomp/ps2xRuntime/src/lib/ps2_vif1_interpreter.cpp`,
and the capture fixtures in the m34–m37 device-log drops.**

**Overall gate: GO** — for the design sketched in §7 (interpreter-driven
segment trains inside one job, funnel prologue paid once per train,
per-segment semantic setup retained, kill switch, offline arbitration
before any device build). The shape M36 §7 guessed — coalescing
same-entry continuations *at the callback layer* — is superseded by
the Q1/Q3 evidence: the callback cannot see the next segment (the
interpreter decodes it after the callback returns), and "same entry
back-to-back" is the wrong unit. What survives of §7 is its objective
and its locus constraint (no VU cycle changes); the merging agent has
to be the interpreter's segment loop plus a light re-entry in the
core. Three prerequisites are part of step 1, not blockers to the
gate: an ordered per-call sequence instrument (Q1), finer ADMA
observability (Q5), and adversarial fixtures (Q6).

**The prize, priced from the m37 session:** the gap is 3.174 µs/run ×
470 runs/field = **1.49 ms/field** (17.1 % of worker time; at m36's
price level and density it was 2.779 × 592 ≈ 1.65 ms/field). Walls are
1,976,366 of 2,752,334 invocations (**71.8 %**; ≈338 of 470 per field).
Under the most conservative breaker assumption in Q1, ≥70 % of funnel
entries are eliminable, worth an estimated **1.0–1.2 ms/field** of the
1.49 — roughly 15–18 % of total callback time — before any per-segment
semantic cost that must stay. No other open lever in the tree is
within an order of magnitude of this pool.

---

## 0. Evidence base — and one correction the fixtures force

The capture fixtures (`files/vu-capture/` in every drop, m34 through
m37, plus the older owner runs) contain five `.bin` images,
`calls.txt`, and `entries.txt`. The capture code
(`captureVu1Program`, ps2_vu1_core.cpp) defines what those files are,
and it is not what their names suggest:

- `entries.txt` gains a line **exactly once per (image, entry) pair
  per process**, on first sight (bitset-guarded). The m37 file's 462
  lines are 21 accumulated process captures of the same 22-pair
  first-seen sequence, not an ordered per-call trace. `calls.txt`
  ("calls 2700000 entries 22") is the capture's call counter,
  rewritten every 100,000 calls.
- So **no artefact in the tree records true per-call adjacency.** Any
  Q1 answer that claimed an exact train-length distribution from
  these files would be fabricated. What the evidence does support is
  stated in Q1 as bounds, plus one cheap instrument that closes the
  gap in step 1.

Everything else below is from code or from the m37 session's own
instrumentation ([ee-prof], [vu1] joins, [stall], [pathb] lines),
cross-checked against the m36/m35 analyses.

---

## Q1. Same-entry run-length distribution — how much merging headroom actually exists?

**Evidence.**

1. **The vocabulary is closed and tiny:** 22 (image, entry) pairs
   total across every capture ever taken (5 images; the walls are
   0x0358 on cd8f, 0x0520 on 2cfd, 0x0520 on 4dfe). The game never
   enters VU1 anywhere else.
2. **First-seen order is a fixed cycle, identical in all 21 process
   captures:** per pass the entries present in one fixed order
   (per image: setup entries 0x0000/0x00b8/0x00c8 → the wall →
   0x2000/0x20c0/0x2338/0x2378-class continuations). In the *light*
   sections where first-seen completes, a wall recurs once per
   22-call pass. This is the pass structure of the game's VIF stream,
   not its crowd density.
3. **Crowd density, from the m37 log's per-entry aggregates:** the
   three walls are 71.8 % of all invocations (0x0358: 743,577;
   0x0520-4dfe: 608,026; 0x0520-2cfd: 624,763; of cb = 2,752,334).
   Per field: ≈338 wall invocations vs ≈132 non-wall invocations.
   Therefore the mean number of non-wall invocations between two
   consecutive wall invocations is **132/338 ≈ 0.39** — in crowd
   gameplay, wall invocations arrive effectively back-to-back in the
   callback sequence, separated on average by less than one other
   invocation, and the only worker-side work between two same-entry
   MSCALs is VIF stream decode plus the next object's UNPACK data
   (interpreter work, not a VU invocation). This is exactly M36 §7's
   "consecutive invocations of the same entry with only
   packet-processing between them", confirmed at the aggregate level.
4. **MSCAL semantics make same-entry trains structural, not
   incidental** (ps2_vif1_interpreter.cpp, VIF_MSCAL/MSCNT cases):
   each MSCAL hands the callback (startPC, runTop, runItop) after
   advancing TOPS and toggling the double-buffer bit — the game's
   per-object streaming pattern *is* a train of same-PC MSCALs with
   alternating buffers, with UNPACK data between them in the stream.
5. **What is not measurable today:** the exact train-length
   distribution (how many wall MSCALs occur between two train-breaking
   events in a crowd field). No ordered trace exists (§0). The
   distribution is one instrument away: the capture hook already
   sees every (image, entry) — a bounded ring buffer recording the
   sequence (plus a marker at job boundaries and at MPG/MSKPATH3)
   for ~100k calls, dumped once, gives the exact distribution
   offline-replayably. This is step-1 prerequisite #1; train
   parameters must not be fixed before it lands.

**Headroom bound (the number the design may plan against):** if every
non-wall invocation were a train breaker — the most conservative
assumption available — a field's 470 funnel entries collapse to
≤132 spans, i.e. **≥72 % of funnel entries eliminable**. Every
non-wall invocation that is *not* a breaker (setups are part of the
train; see Q3) only improves that bound. Expected recovery
**1.0–1.2 ms/field** of the 1.49 ms/field gap pool at m37 prices,
holding back the per-segment semantic setup Q2 says must stay.

**Q1 verdict: headroom is real and large, but it is train-shaped,
not pair-shaped.** Zero evidence supports same-entry back-to-back
*pair* merging as the unit (in first-seen order it never occurs; in
the callback sequence the walls arrive as trains, which is a
stronger and different statement). Exact train lengths: unmeasured,
instrument specified.

---

## Q2. Per-invocation fixed-work inventory — what merging amortises, priced where pricing exists

Read from the shipped call path: VIF interpreter → MSCAL/MSCNT
callback lambda (ps2_runtime.cpp) → `execute()`/`resume()` →
`runFused()` → `runLoopWith()` (ps2_vu1_core.cpp). Per invocation, in
order:

**Callback layer (ps2_runtime.cpp, `setVu1MscalCallback` /
`setVu1MscntCallback`)**
1. Worker/EE context resolution (`onWorker()`, context fetch) and
   FBRST fetch (job snapshot on the worker path).
2. D-bit/T-bit enable writes into VU state from FBRST.
3. `eeProfT0` steady-clock read (rider stamp).
4. `finishVu1Program` budget probe per slice (in gameplay: slices = 1
   always — [ee-prof] shows budget 0 in every m37 window).
5. `recordVu1Callback`: second clock read + atomic record.
6. VPU_STAT stop-bit writeback — **EE context only; skipped on the
   worker path** (cpuContext is null when `onWorker()`), so the
   walls never pay it.

**execute() prologue (MSCAL only)**
7. Scheduler-reset decision (`m_vu1LeanClean` check; `resetScheduler()`
   when the previous run dirtied state — the lean skip already exists).
8. State init: pc, ebit, halt flags, stoppedByD/T, top/itop, branch
   state, vf[0] reset (~20 stores). **Semantic — stays per segment.**

**runFused() prologue**
9. Resident prefetch set (5–7 prefetch instructions; side-effect-free).
10. `captureVu1Program`: capture-armed probe; per call it does the
    (memory, generation) check and the entry-bitset probe (cached
    per (memory, generation) since m35).
11. `traceVu1Run` gate probe.
12. Verify gate: env flag + registry lookup (verify is off in the
    shipped config; the flag check remains).
13. `vu1FrameStatsBegin` gate; when stats are on, a packet-sink is
    constructed per run and GIF tags are counted after it.
14. `lookupCompiledVu1Program`: registry hash-map probe keyed by
    (code pointer, generation). **Stays per segment whenever the
    image or generation changes; cacheable within a train when both
    are unchanged (the common wall-train case).**

**runLoopWith() entry**
15. Active-pointer stores (m_activeVuData/Size/Gs/Memory).
16. Host FP environment sync: MXCSR read + conditional rounding-mode
    set, per run.
17. `EeProfVuRun` guard construction: clock read + hash-cache probe
    (env-gated under the slim funnel; the shipped config runs with
    PROF_RUNS on, so it is paid).
18. Budget computation (budgetEnd), loop entry/exit, and the
    function-call chain itself (callback → execute → runFused →
    runLoopWith → compiled program), with its cache/TLB re-entry on
    the interpreter state block and image lines.

**Pricing.** The log prices the sum, not the split: pre **0.103**
µs/run + post **0.092** (items 1–6's out-of-span part) and residue
**2.979** µs/run (items 9–18 plus re-entry) at m37 prices; gap total
3.174 (m36 price level: 2.779, pre 0.091 / post 0.084 / residue
2.604). The split inside the residue is *not* priceable with current
instruments, by the tree's own prior findings: the m34 rider
elimination showed every named instruction suspect ≤ ~0.1 µs/run,
and the host micro-bench is resident-hot and cannot price re-entry
(M36 report's bench blindness). Anyone quoting a per-item price
beyond the pre/post/residue split is guessing.

**What a train pays once instead of per segment:** items 9–13
(gates/probes), 15–17 (runLoopWith entry), and the re-entry share of
18. **What stays per segment:** item 8 (state init), item 14 when
image/generation changes, per-segment cycle accounting, and items
1–6 (the callback still fires per segment — the train lives below
it; see §7).

**Q2 verdict: inventory complete, total priced (2.78–3.17 µs/run
depending on the session's price level), split honestly unpriceable.**
The amortisable share is the funnel prologue + runLoopWith entry +
re-entry; the irreducible share is per-segment state setup and
re-resolution on image change. Even if the irreducible share were
half the gap — far above what the item list suggests — the train
still returns ~0.75 ms/field.

---

## Q3. Flush boundaries — what ends an invocation, hard vs soft

An invocation ends for exactly three reasons in the code: the
program reaches its E bit (the normal case — budget cuts are 0 in
every m37 gameplay window), a D-bit/T-bit stop fires, or the
65,536-cycle slice budget cuts it (then `finishVu1Program` resumes
inside the same callback, ≤16 slices). The merging question is which
stream/job events a train may not cross. From the interpreter, the
callback layer, and PATH-VUJOIN's join map, with the m37 session's
own [vu1] joins attribution as the frequency evidence:

**HARD — a train must break (or never spans them by construction):**
- **H1. Job boundary.** A job is one `processPendingTransfers`
  batch; beyond it the work is not yet submitted and the EE may
  join. GIF publish and job accounting also happen there. Trains
  live inside one job, full stop.
- **H2. MPG microprogram load.** Bumps the VU code generation; the
  trained image may be overwritten. Break + re-resolve the compiled
  program (item 14 becomes mandatory). Frequency in the fixture
  vocabulary: the generation is stable across whole sessions (the
  capture's 22 pairs persist), so this is rare — but it is the one
  in-stream event that invalidates code, so it is hard.
- **H3. EE join/drain points** (`asyncvif::sync()` sites): register-
  space **writes** (regwrite: 15–22 waits, 2.1–3.0 ms in m37
  gameplay windows), **GS-priv writes** (gspriv: 32–104 waits,
  11.7–57.3 ms — the dominant EE sync in the session), **VU-memory
  maps** (vumem: 0), **download setups** (download: 0), and the
  **vblank barrier** (barrier: up to 310 waits / 97.0 ms in the
  heaviest gameplay window; idle bucket up to 103.2 ms / 169 waits).
  All of these drain the *queue*, so by construction they can only
  occur between jobs — a train never spans one. Listed because they
  bound the design space: nothing about merging may defer work past
  any of them, and it doesn't.
- **H4. D-bit/T-bit stop.** A program that stops on D/T must surface
  the stop at that segment (the sweep-R2 comment in ps2_runtime.cpp:
  the next MSCAL restarts VU1 and the rest of the batch would be
  lost). The train checks stop state at every segment end and
  breaks there. Cheap to honour, wrong to merge through.
- **H5. MSKPATH3 unmask.** `flushMaskedPath3Packets()` publishes
  held PATH3 packets; VU (PATH2) output order relative to PATH3
  must be preserved. Train breaks at the toggle so publication
  order is byte-identical.

**SOFT — mergeable with bookkeeping:**
- **S1. TOPS/ITOP/DBF updates.** The interpreter computes runTop /
  runItop per segment and advances TOPS / toggles DBF *before*
  firing the callback. A train passes them through per segment —
  they are the train's payload, not an obstacle.
- **S2. UNPACK data uploads between segments.** Processed by the
  interpreter inline, in stream order, on the same thread. The
  train does not skip the interpreter; it skips the funnel
  re-entry. The data dependency is preserved by construction.
- **S3. FLUSHE / FLUSH / FLUSHA.** In this interpreter they are
  literal no-ops (`continue`) — VU already ran synchronously inside
  the MSCAL callback, so there is nothing to wait for. Not breakers.
- **S4. The cycle-slice structure.** `finishVu1Program`'s
  resume-chain already continues a program inside one callback;
  a train keeps per-segment budget accounting identical (and in
  gameplay the budget never fires).

Also recorded from the joins lines, because it closes a door:
**regread = 0 in every m37 window** (live register reads hold),
backpressure ≈ 0 (one 0.3 ms event in one window; queue cap 64,
queue_hw stays ~2 per PATH-VUJOIN) — the queue is never the
constraint, and merging must not become a queueing change.

**Q3 verdict: five hard boundaries (H1–H5), all either job-external
or rare in-stream events; the dense middle of the stream — UNPACKs,
FLUSHes, TOPS/ITOP churn — is soft.** Nothing in the boundary list
stands between consecutive wall segments inside a job except H2/H4/
H5 checks, each a cheap per-segment test.

---

## Q4. Job-length cap vs the 2-field pipeline

**Structure.** The GS bridge pipeline is 2 fields deep
(PATH-VUJOIN §5: "pipeline on at depth 2 fields"). The EE submits
field N's jobs while earlier fields drain; a join returns only when
the in-flight job finishes, so **the job is already the EE's
latency unit**: m27 measured per-wait ≈ one crowd job (~7–8 ms
wall); the m37 session's mean job is 51,187.3 ms of worker time /
(2,752,334 runs / 309 runs-per-job ≈ 8,907 jobs) ≈ **5.7 ms**, at
≈1.52 jobs/field and 470 runs/field. The vblank barrier waits in
250 µs slices (100 ms cap) for full pipeline idle. Per field the VU
owes ≈470 × 1,808 ≈ 850k cycles ≈ 2.9 ms of VU time — the VU is
not the field's throughput bottleneck; join latency is the budget
that matters.

**Derivation.** A train lives inside one job (H1) and performs
exactly the job's own cycles in the job's own order. It therefore
cannot lengthen any wait the EE already pays: worst-case join
latency stays one job, identica
...[truncated 6158 chars]