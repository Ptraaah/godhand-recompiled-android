# Progress log — from first boot to the GPU renderer

The whole run, start to current state, with the numbers the phone actually produced. Device for everything: POCO F5 (Snapdragon 7+ Gen 2, Adreno 725), my only phone. Disc: my own US copy of God Hand (SLUS-21503), hash-verified by the app on every install. The detailed engineering reports for every step live in [docs/milestones](milestones/) — this page is the story they tell.

## Chapter 1 — the game becomes one program

The recompilation toolchain translates the PS2 program into native code ahead of time. For God Hand that is 10,554 functions in the main program plus 81 overlay modules, generated from about 9 MB of the disc (the boot program, the file table and the overlays — not the 1.57 GB image). The translated code, the runtime and the game layer link into a single program: the translation proof held, whole, on the first full build.

## Chapter 2 — a shell, and the first picture

The Android shell came next: a launcher that picks the disc image from phone storage, a game activity, touch controls with an editable layout, gamepad support, and settings that persist — the sealed-build behaviour of choices silently resetting is the specific thing this shell exists to not do.

The early builds failed honestly. This is the 0.3 launcher with a boot that died at mounting the disc image:

![The 0.3 launcher: ISO picked, boot failing at the disc mount](images/launcher-0.3-boot-fail.jpg)

The breakthrough was build 0.4: the disc mounted, the boot program and its modules loaded, the sound stack came up, and the game produced its first real picture on the phone — the Dolby Pro Logic II splash from the intro sequence.

![First real picture on the phone: the Dolby splash, build 0.4](images/first-picture-dolby.jpg)

From there the shell grew teeth: landscape fullscreen, a touch overlay in its own window (the first version was invisible — window layering), a settings page (resolution scale, FPS counter, thread policy, vsync, overlay opacity and size), a layout editor and button remapping. The game booted to its title screen and sat there at **1 FPS, worst frame 1,328 ms**.

![Title screen with the full touch overlay, running at 1 FPS](images/title-shell-era-1fps.jpg)

## Chapter 3 — measuring instead of guessing

The 1 FPS title started a long audit, and the audit's first lesson was humility: its prime suspect (a boot-time disc prefetch saturating storage) turned out to be innocent — the prefetch had been disabled in the glue since the first builds and never ran. The replacement disc cache (asynchronous, background worker) did cure the streaming scenes: the Sofdec/ADX logo sequence went from 4 FPS to 60 FPS between builds 0.6 and 0.8. But the title did not move.

So the present path got split into timed buckets — sync, gs-fetch, draw, swap — printed every five seconds. The verdict, at the title screen:

```
[frame-split] presents=7 avg 761.8 ms fields +282 | sync 0.00 ms (0%) gs-fetch 760.66 ms (100%) draw 0.19 ms (0%) swap 0.85 ms (0%)
```

gs-fetch — the leg where the CPU renderer fetches and presents the finished GS frame — ate 99-100% of every frame. Everything else was measured and acquitted: the emulation fields kept advancing, the IOP chip idled at 12-29%, the music stream never starved, and the resolution scale setting turned out to be a no-op on the CPU backend (it always rendered at native 640x448). The CPU renderer was a dead end for speed, however correct its pictures were.

![The title on the CPU renderer in the final CPU builds: still 1 FPS](images/title-cpu-1fps.jpg)

## Chapter 4 — building the GPU renderer Android never had

The port already contained a GPU renderer, some 5,800 lines — but its graphics context existed only for Windows. On any other platform it returned an error and latched off. Build 0.11 turned the port's own GPU switch into a launcher toggle and proved the prize on the phone: with the fetch leg bypassed, the machine ran empty presents at 16.7-18.9 ms, about 60 a second. The pipe was fast; only the drawing was missing.

0.12 built the Android door: an EGL OpenGL ES 3.2 context owned by the graphics layer itself (this backend renders into buffers and hands back pixels, it never draws to the window, so its context lives on a small private surface), and a load-time translation of the desktop shaders from `#version 460 core` to `#version 320 es` — all nine shaders pass a real GLSL validator after translation. First run on the phone: 57 FPS, completely black screen.

![First GPU run: full speed, no picture](images/gpu-first-run-black-57fps.jpg)

The log named the wall in one line — `missing GL functions: glGetBufferSubData, glGetQueryObjectui64v, glQueryCounter` — and tracing it found the rude part: every EGL step had already succeeded on the phone, then the function-table check destroyed the working context and latched the backend off for the session. 0.13 resolved the three the ES way (the buffer read is real data path and got a map-based implementation; the timer queries bind their genuine EXT entry points on this driver, with safe fallbacks), and made every failure in the backend print its reason out loud.

The 0.13 boot chain, verbatim from the phone:

```
[gs-gpu] ES entry points: glGetBufferSubData = map shim (no ES entry point exists); timer queries = GL_EXT_disjoint_timer_query (glQueryCounterEXT / glGetQueryObjectui64vEXT)
[gs-gpu] EGL context created: OpenGL ES 3.2 ... | Adreno (TM) 725 | max SSBO bindings 64, max SSBO block 134217728 bytes, EXT_buffer_storage yes
[gs-gpu] shader compile summary: 9 compiled, 0 failed
[frame-split] presents=300 avg 16.7 ms fields +300 | sync 11.68 ms (70%) gs-fetch 3.96 ms (24%) draw 0.21 ms (1%) swap 0.78 ms (5%)
```

Title and menus: a real 60 FPS, drawn by the GPU. And the game world itself renders through the new renderer:

![In-game: the starting village on the GPU renderer](images/ingame-village-20fps.jpg)

![Loading screen on the GPU renderer](images/loading-19fps.jpg)

## Chapter 5 — the current wall

In-game, the guest completes only about 8-16 fields per 5-second window (menus: ~300), while the host waits in sync and re-presents near-identical frames. The counter reads 19-20 FPS; the world moves at 1-3 new frames a second. It looks frozen, and saying so plainly matters more than the counter.

The standing suspects are the per-field graphics work under real scene load: synchronous texture readbacks (each one can drain the whole GPU pipeline) and the compute rasterizer's throughput. The next build instruments the backend worker itself — fields, draws, raster time, readback counts and time, fence waits, worker load, per window — so the fix lands on the measured eater. That is the same method that found every wall so far: the disc cache, the fetch leg, the function table. Measure, then fix the thing that is actually slow.

## The reports

Every step above has a full engineering report, published as written at the time, wrong turns included:

- [SPIKE](milestones/SPIKE.md) · [EXTRACTOR](milestones/EXTRACTOR.md) · [GENERATION](milestones/GENERATION.md) — the toolchain and generation groundwork
- [M1A](milestones/M1A.md) · [M2A](milestones/M2A.md) · [M2B](milestones/M2B.md) · [M2C](milestones/M2C.md) — translation proof and the first Android builds
- [M3](milestones/M3.md) · [M4](milestones/M4.md) · [M5](milestones/M5.md) — shell, controls, settings
- [AUDIT-M6](milestones/AUDIT-M6.md) · [M6](milestones/M6.md) · [M7](milestones/M7.md) · [M8](milestones/M8.md) — the cache, the audit, the acquitted suspects
- [M9](milestones/M9.md) — the frame-split verdict on the CPU renderer
- [M10](milestones/M10.md) · [M11](milestones/M11.md) · [M12](milestones/M12.md) — the GPU toggle, the EGL bring-up, the function-table wall

## Ground rules that have not changed

- No game data in this repo, ever. You bring your own legitimate US disc; the app verifies it by SHA-256 and refuses anything else.
- Not playable yet: menus are smooth, the world renders, in-game frame production is the open fight. When that changes, this page changes.
