# M29 (0.30-m29-vujoin2) device log analysis

Source: Pablo's upload 2026-11-06 ~01:20 WITA (~/workspace/gh-m29-log/files/godhand-boot.log, last session, boots at line ~8380). Owner verdict: "Masih nge lag godhand nya belum playable dan stabil, suara masih patah patah" — matches the numbers below.

## Test validity (first clean VU-join test)
- Boot shows `[gs] renderer: PCSX2 GS (libpcsx2gs.so, Vulkan)` and `[pcsx2-gs] pipeline on ... depth 2 fields`. The ini poison from m28 is gone; the variant seed fired.

## The lever fired exactly as designed
- `[vu1] joins:` regread = 0.0 ms (0 waits) in EVERY window. The register-read full drains are eliminated.
- Light windows: join_wait 5.8-6.6 ms/window total (vs m27 light ~35-62 ms parked sync alone; m27 crowd joins 507-1,694 ms).
- queue_hw now reads 64 (the real backpressure cap) instead of 2 — confirming PATH-VUJOIN.md's claim that hw=2 was a drain fingerprint, not a cap.

## But the crowd wait migrated, 1:1, into the vblank barrier
Heavy windows (5 s each), join_wait is now dominated by the `idle` site (= waitPipelineIdle at the scheduler's vblank barrier):
- 774.3 ms total, idle 770.4 ms (2,426 waits)
- 670.9 ms total, idle 664.3 ms (2,004 waits)
- 1,366.9 ms total, idle 1,365.2 ms (4,124 waits)
- 1,589.9 ms total, idle 1,588.6 ms (4,868 waits)
- 823.6 ms total, idle 822.0 ms; 551.6 / 551.4; 428.6 / 427.1; 409.2 / 408.9
All other sites ~0 (gspriv 0.2-6.1 ms, regwrite ~0, vumem 0, download ~0, backpressure 0). Thousands of ~0.3 ms micro-waits: the barrier is hit constantly and spins until the pipeline truly drains each field.

This is the exact failure signature PATH-VUJOIN.md §risks named in advance: "the vblank barrier may inherit the freed wait into the idle bucket."

## Throughput outcome (why Pablo's verdict is right)
- Session [ee-work] fields/s: light 57.9-59.9; heavy windows 31.0-51.3 (min 31.0; most 44-51). NOT better than m27's heavy band (39.4-58.6) — the barrier re-serializes what the live reads freed, so crowd feel and audio choppiness are unchanged.
- Audio: still slaved to guest timing; uneven crowd field production = choppy audio. No separate audio fault.

## Next lever (named by the attribution line)
The vblank barrier's full-drain semantics: waitPipelineIdle() drains the ENTIRE VU/GIF pipeline every field. On real hardware vblank does not wait for VU1/GIF completion; the guest synchronizes through its own polls (now cheap/live) and through true data dependencies (VU-memory reads, downloads) which still drain at their own sites and are untouched by any barrier change. Stage goal: bound or remove the barrier's drain (e.g., wait only for work submitted before the previous field, or skip when queued work is younger than the barrier), with a kill switch, keeping vumem/download/gspriv drains intact. Success signature: crowd idle-bucket join_wait collapses like regread did, crowd fields/s 55-60, light windows unchanged.
