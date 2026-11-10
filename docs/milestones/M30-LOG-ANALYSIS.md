# M30 (0.31-m30-vblank) device log analysis

Source: Pablo's upload, extracted at ~/workspace/gh-m30-log/files/godhand-boot.log (9,632 lines, appended log; the m30 session is the last one — settings from line ~9005, guest start line 9185, 23 [ee-work] windows ≈ 115 s of windowed play, 6,035 fields total). Owner verdict: "Udah lebih enak overall tapi masih ngelag dan patah patah suaranya juga." The numbers below agree with all three parts.

## Test validity
- Line 9012: `PS2X_GS_RENDERER=pcsx2 (wins over godhand.ini native)` — the compiled-in variant seed fired; the m28 ini poison is overridden again. Line 9035: `[gs] renderer: PCSX2 GS (libpcsx2gs.so, Vulkan)`. Line 9191: `[pcsx2-gs] pipeline on ... depth 2 fields`. PS2X_GS_SCALE=2 (env over ini 3).
- VU1 async on, queue_hw = 64 in all 23 windows (the real cap, as established in the VU-join stage).
- The [vu1] joins attribution carries the new `barrier=` bucket in every window — the m30 stage code (PS2X_VU1_VBLANK_BOUND) is live in this build, not just compiled in.

## The vblank lever fired — and the wait shrank instead of vanishing
- `regread = 0.0 ms (0 waits)` in EVERY window. The m28 live-reads lever still holds perfectly; no regression.
- Light windows: total join_wait 1.0-3.4 ms/window (vs m29 light ~5.8-6.6, m27 light ~35-62 of parked sync alone). Light fields are effectively join-free now.
- Crowd windows: join totals ~92-1,230 ms (worst windows 839.6 / 1,033.0 / 1,230.3), down from m29's 409-1,590 and m27's 507-1,694. Composition changed as designed: `barrier=` is now the bounded remnant (111.4-936.1 ms in heavy windows, worst 936.1 ms over 2,825 waits) and `idle=` still carries 54.3-386.2 ms. In crowd steady state even previous-field work is unfinished at the barrier, so the bound converts an unbounded drain into a one-field-stale wait — smaller, but the wait is now backed by real unfinished work, not by semantics.
- All other sites stay ~0 (gspriv 0.2-6.1 ms, regwrite ≤2.6, vumem 0, download ~0, backpressure ~0).

## Throughput outcome (why Pablo's verdict is right)
- Light windows (VU 1-10%): 55.7-59.9 fields/s — at the pacing cap.
- Heavy windows (VU 42-91%): 41.9-57.5 fields/s (min 41.9; most 45-55), a real gain over m29's heavy band 31.0-51.3 and at/above m27's 39.4-58.6 — but NOT the 55-60 stable target.
- VU busy in heavy windows is 57-91% and GIF runs 338-488 MB/window: the crowd wall is now genuine VU/GIF throughput saturation, not a synchronization artifact. The barrier/idle remnant (~165-1,230 ms/window ≈ up to ~5 ms/field of EE block) is the guest waiting on that real work.
- Audio: unchanged mechanism — SPU2 is slaved to guest timing, so uneven crowd field production is still audible as choppiness. No separate audio fault.

## The new first-class suspect: parked present
Every window — light ones included — carries `[ee-work] parked: present` of 667.5-908.0 ms per ~5 s window over ~209-300 Present drains (≈2.3-3.2 ms per present), plus parked sync 35.4-625.5 ms. In light windows the GS worker is 81-85% idle and the VU/GIF load is trivial, yet the present park is the same size as in crowds: it is per-field and scene-independent, i.e. a fixed host-path tax of ~13-18% of every window. [frame-split] agrees from the other side: its gs-fetch bucket (which contains the latch) is 24-35% of every field interval.

That is the next stage: PATH-PRESENT.md traces where those ~2.5 ms per present actually go (answer: full-frame CPU copies inside the bridge frame harvest, not GPU latency and not queue drains), and names the lever.

## Standing
- m27 (0.28-m27-pipeline, versionCode 35) remains the playable fallback anchor until a successor beats it on the device. m30 is a real step over m29 on every arbitrating line, and its log closes the vblank stage: the barrier bound works as designed, and what remains in crowds is throughput.
