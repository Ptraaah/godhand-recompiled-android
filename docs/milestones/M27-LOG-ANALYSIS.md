# God Hand 0.28-m27-pipeline — device log analysis

Analysed: the last session only of the phone's appended
`godhand-boot.log` from `files_1_je9b.zip` (extracted at
`~/workspace/gh-m27-log/files/godhand-boot.log`, 6,212 lines total).
Baseline: the pathb8 session of the previous log
(`~/workspace/gh-b8-log/files/godhand-boot.log`, last session from
line 4441), with the arbitrating metrics as named in
PATH-BRIDGE-PIPELINE.md and PATH-PIPELINE-BUILD.md.

## Session identification

- Boot: 2026-11-05 20:28:39 WITA (touch-overlay timestamp), session
  spans roughly lines 5495–6212 of the appended log. 26 `[ee-work]`
  windows ≈ 130 s of windowed play; the bridge counted 6,600
  executed GS fields in the session.
- `[BOOT] settings`: `PS2X_GS_RENDERER=pcsx2` (variant env over the
  ini's native), `PS2X_GS_SCALE=1`, `PS2X_THREAD_POLICY=auto`,
  `PS2X_VU1_ASYNC=1` (variant default), ARUS on.
- **Build proof: PRESENT.** Line 5689, inside this session:
  `[pcsx2-gs] pipeline on: the GS runs on the bridge thread, depth 2
  fields (PS2X_GS_PIPELINE=0 restores direct calls)` — this session
  really is 0.28-m27-pipeline with the pipeline active at depth 2.
  No `PS2X_GS_PIPELINE=0` seed anywhere in the session.
- One benign boot note: the bridge's shaderc first tries
  `libshaderc_shared.so.1`, fails, and falls back to the unversioned
  library — the same behaviour the b8 log showed; not a factor in
  anything below.

Owner's own verdict on this run, for the record: more comfortable
than b5 overall, still lags when facing crowded areas, audio choppy.
The numbers below agree with all three parts.

## 1. [frame-split] — presents and the sync share

26 windows. Fields per window track presents ~1:1 throughout (no
field skipping on the present path).

| | m27 light (windows 1–3) | m27 heavy (sustained) | b8 light | b8 heavy |
|---|---|---|---|---|
| presents avg | 16.7–19.6 ms | 20.0–25.8 ms | 16.8–22.2 ms | 22.2–30.5 ms |
| sync | 14.8–17.5 ms (86–89%) | 17.5–23.9 ms (91–93%) | 12.7–18.2 ms (73–82%) | 16.0–26.5 ms (78–88%) |
| gs-fetch | 0.83–0.91 ms | 0.65–0.79 ms | 2.02–2.66 ms | 1.85–4.76 ms |
| swap / draw | ~0.8 / ~0.22 ms | ~0.7–0.8 / ~0.21 ms | ~1.0 / ~0.25 ms | ~1.0 / ~0.27 ms |

Two readings matter here:

- **gs-fetch collapsed** (0.65–0.91 ms vs b8's 1.85–4.76 ms): the
  presenter now gets its frame from the bridge cheaply. One
  exception, window 5 (sync 15.18 ms but gs-fetch 3.57 ms), matches
  the harvest window with `suppressed=85` — a transition burst, not
  gameplay.
- **The sync share must not be misread.** `[frame-split]` sync is
  the host present loop waiting for the next field tick to exist
  (S7 in the pipeline report — a pacing wait, not a bridge drain).
  Its share *rises* to 91–93% in crowd windows precisely because
  fields arrive late: the presenter is idle-waiting on production.
  The conviction metric for the old coupling disease is
  `[ee-work]` parked sync — next section — and that collapsed.

## 2. [ee-work] — the conviction metric, and where the time went

Parked time per ~5 s window (count/ms):

| bucket | m27 light (win 1–3) | b8 light (win 1–3) | m27 heavy (sustained) | b8 heavy (sustained) |
|---|---|---|---|---|
| parked sync | **45.1 / 62.4 / 48.2** | 416.3 / 378.9 / 315.0 | **18.0–34.6** | 196.9–719.0 |
| parked present | 120.7–130.1 | 204.3–318.7 | 82.6–111.9 | 89.5–486.7 |
| parked consume | 0.0 | 0.0 | 0.0 (two mid windows: 41.6, 50.7) | 0.0 (one fight window: 133.9) |
| queueWait avg | 0.10–0.19 ms | 0.52–1.95 ms | 0.09–0.34 ms | 0.49–9.84 ms |
| gsIdle | 96–97% | 84–87% | 89–94% | 66–79% |

**S1 is convicted as fixed.** The per-field double drain that cost
~1.1–1.5 ms per field in b8's light windows and up to 719 ms per
window in its fights now costs 18–62 ms per window — a 10–20×
reduction — and the queue pickup latency fell with it. Light
windows run at the full guest rate: 59.7 / 58.4 / 59.9 fields/s
(b8's light windows: 49.4–59.8, bought with ~10× the parking).

Fields/s, heavy windows: **m27 39.4–58.6** (sustained crowd
windows, VU ≥ ~47%: 39.4–48.9) vs **b8 31.3–50.4**. A real
improvement of roughly +5–10 fields/s in the same territory, and
the b8 line's sub-32 collapses are gone — but the 60 ceiling is not
reached in crowds, and §6 names what holds it.

Other heavy-window figures (m27): guest 76–89%, VU 32–55%
(297–439 jobs), GIF 4,225–7,361 jobs carrying 229–390 MB per
window — *more* GIF traffic than b8's heavy windows (170–295 MB)
at *higher* field rates: the pipeline is being fed more work per
second, not less. One anomalous window (the 5th: sync park 855.1 ms
with `callExec` 652 calls / 953.7 ms and the session's only heavy
vif traffic, 234 jobs) is a one-off worker-side event, not the
steady state; it is excluded from the sustained ranges above.

`queueWait max` reads 1410.5 ms in every window — a single
boot-window spike carried as a cumulative maximum, the predicted
b5-form lazy capture-ring cost. Not a fight metric in either log.

## 3. [pathb] pipeline — the depth bound never binds

22 windows, fields 300–6600:

- `ahead_max=1` in **every** window. The queue never held even the
  full depth of 2 fields, at any point in the session.
- `park`: 470.2 ms total across the whole session (28 waits), and
  443.1 of those ms sit in two early transition windows (fields
  1200 and 1800: 234.1 ms / 12 waits, 209.0 ms / 11 waits). From
  field 2100 to the end — the entire crowd stretch — park is
  **0.0 ms in 16 of 18 windows** (exceptions: 9.0 ms once, 1.1 ms
  once).
- `dropped_captures=0` in every window.

Verdict on this item: in crowd scenes the producer does **not**
park on the depth bound. The bridge thread finishes each field
before the next one arrives; the pipeline is starved, not
saturated. `PS2X_GS_PIPELINE_DEPTH` is exonerated as a lever for
the crowd problem — raising it would deepen a queue that never
fills. (Depth remains a latency knob for feel, nothing more.)

## 4. [pathb] harvest — readbacks stay in family

26 windows: `readback_wait` **67.4–106.3 ms** per 5 s window,
against b8's 50.8–105.5 ms. Grabbed 185–281 per window, suppressed
5–85 (the 85 is the §1 transition window). No jump, so the capture
mutex is not indicted; true readbacks are not the crowd problem,
and `parked consume` (§2) agrees — essentially zero outside two
small mid-session windows.

## 5. Audio — what the log can and cannot say

The binary's log vocabulary has **no underrun / xrun / dropout
counter**; no such line exists in this session, so choppiness
cannot be convicted from a dedicated audio line. What exists:

- `[spu2-silence]` events at t=4.665 s, 6.187 s, 6.722 s, 21.501 s,
  22.072 s and one `[spu2-adma]` event (core 1 stream gave 892
  silent blocks, 4,757.3 ms, ending t=9.424 s) — all inside the
  first ~22 s (boot / title / loading), none during the gameplay
  windows. These are stream-content silences, not device starvation
  reports.
- Mechanically, the owner's choppy audio in crowds is consistent
  with the pacing evidence and needs no second cause: SPU2 output
  is slaved to guest timing, and crowd-window field production is
  uneven — fields/s swings 39–59 window to window, and §6's VU
  joins stall individual fields by up to ~9.5 ms a time. Choppy
  audio here is the field-rate dips made audible. Its fix rides on
  the same lever as the crowd lag; there is no separate audio bug
  in this log.

## 6. VERDICT — in crowd scenes, VU1 binds; the pipeline is done

Constraint ranking for the crowd windows, with the convicting
numbers:

1. **VU1 throughput / dependency joins — BINDS.** `[vu1] async on`
   in every window (`PS2X_VU1_ASYNC=1`), `queued≈run` (no runaway
   backlog), `queue_hw=2` throughout — and `join_wait` per 5 s
   window: **35.0–44.8 ms in light windows, 507.0–1,694.3 ms in
   crowd windows** (worst: 1,694.3 ms across 179 waits in the final
   window; several windows at 1,245–1,350 ms). Per-wait cost grows
   from ~0.37 ms (light) to ~7–9.5 ms (crowd): the EE submits a VU
   job, has roughly two queued jobs of independent work, then parks
   until that job completes — and crowd geometry makes each job
   ~20× slower to complete. In the same windows the pipeline park
   is ~0 ms and the coupling sync park is 18–35 ms: the EE's big
   park bucket moved, wholesale, from the GS coupling to the VU
   join. Supporting evidence: VU busy 32–55%, GIF traffic peaking
   at 7,361 jobs / 389.6 MB per window, guest 76–89%.
2. **Native EE time — the wall behind it.** Guest execution is
   76–89% busy in crowd windows at constant ~4.92M cycles/field;
   once joins shrink, raw EE throughput is the next ceiling. It
   does not bind first today because the EE still spends
   0.5–1.7 s per window parked on the VU, not executing.
3. **Queue depth — EXONERATED.** `ahead_max=1` in all 22 windows,
   park ≈ 0 across the crowd stretch (§3). Do not spend a build on
   `PS2X_GS_PIPELINE_DEPTH`.
4. **GS / GPU work — EXONERATED.** `gsIdle` 89–97% (b8: 66–87%),
   gs-fetch 0.65–0.91 ms, `dropped_captures=0`, readback_wait in
   its historical range (§4). The GPU side is waiting for work,
   not withholding it.

This is the landing the pipeline report pre-named: "the B7
forensics' other binding constraints — [vu1] join_wait and native
EE time — are core/VU territory… If they bind once the coupling
waits fall, fields/s plateaus below the ceiling and the next stage
belongs there." The coupling waits fell 10–20×, light scenes hit
the 60 ceiling, crowd scenes rose ~+5–10 fields/s — and the
plateau that remains sits exactly on the VU join.

**Single most promising next lever: cut the VU1 join stalls in the
core** — deepen the EE↔VU1 overlap so the EE's dependency joins
land later (the async queue's `queue_hw=2` is the shallow point),
and/or reduce per-job VU cost on crowd geometry in the AOT VU
programs. The MTVU state is already visible in this log and is
*not* a missing toggle: async is on and jobs do run concurrently;
what's missing is overlap depth and job speed, not the switch.
This is a core-stage change (the pipeline stage deliberately did
not touch the core), and it should be scoped against these exact
lines before any build.

Expected log signature if that lever lands, same route and
protocol:

- `[vu1] join_wait` in crowd windows falls from 507–1,694 ms
  toward the light-window scale (< ~150 ms per window).
- Crowd-window fields/s rises from 39–49 toward 55–60; `[ee-work]`
  guest% rises as parked time converts to execution.
- `[frame-split]` presents avg falls toward ~17 ms and the sync
  share drops back under ~90% (fields arriving on time again).
- `[pathb] pipeline: ahead_max` finally touches 2 in crowd
  windows — the pipeline being fed faster than it drains is the
  *proof* the producer side was fixed — with park still a small
  fraction of the old totals and `dropped_captures` still 0.
- Harvest `readback_wait` unchanged (67–106 ms family); if it
  jumps, the change hurt the wrong thing.

Until then, 0.28-m27 stands as the best-feeling build on the
device evidence: the waiting room is gone, light play is at the
ceiling, and what remains in crowds is honest work — the VU
transforming crowd geometry slower than the EE needs it.
