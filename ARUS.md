# ARUS

*A pipeline for recompiled PS2 games on mobile GPUs. God Hand is the first game running it.*

I am ptraaa. I recompile PS2 games to run natively on Android phones, and for a
long time my God Hand build had the same problem every recompiled PS2 game has
on a phone: the menus ran fine and the fights fell apart. Frame rates in combat
sat at one or two frames per second on a phone that is not slow. This page is
the explanation I eventually arrived at, and the system I built on top of it.

## The diagnosis, in one sentence

The PS2's graphics pipeline was designed for a fixed-function chip wired
straight to its own memory, where every order is carried out the moment it is
issued — zero latency — while a mobile GPU is a queued, high-latency machine
where work waits in line, and every stall I ever measured in a recompiled PS2
game on a phone is that one false assumption breaking, in a different place
each time.

On a real PS2, the Graphics Synthesizer does not queue. An upload lands, a
draw happens, a read returns, a frame shows up — each roughly when it is asked
for. A phone GPU does none of that. It batches, it defers, it retires work
seconds after it was submitted if the queue is deep enough. A renderer that
issues PS2-style orders and then *waits* — for a fence, for a readback, for a
buffer to come back around — pays the whole queue's depth every single time.
That is the whole problem. Not the CPU. Not the translation of the game code.
The queue, and a design that pretends there isn't one.

ARUS is my answer: a set of four mechanisms that rewrite the rules the GS
pipeline plays by, so the work fits how a mobile GPU actually behaves. Each
one exists because a device log convicted the thing it fixes. The numbers
below are from my own phone, a POCO F5, in real fights.

## The four pillars

### 1. Do not upload the same data twice

Games re-send the same textures constantly — the same effect sprites, the same
frames of an animation, field after field. The original pipeline dispatches
every one of them into VRAM again. ARUS hashes each staged upload and
remembers what is already there: if a chunk's destination pages still hold
byte-identical content and nothing has drawn over them since, the upload is
skipped outright. Dispatching it again would write the same bytes, so not
dispatching it changes nothing on screen — it just stops paying for it.

Measured: in fights, the dedup skips roughly 30 percent of the upload volume
(about 19-22 MB skipped against 45-49 MB actually uploaded, per accounting
window). Honest note: it helped, and it was not the main problem. Fights kept
stalling. That is how pillar 2 got found.

### 2. Shade at the rate the screen can use

A phone GPU rasterising a PS2 frame at native resolution does an enormous
amount of per-pixel work the original chip did in dedicated hardware. ARUS
lowers the *shading rate*: drawn content is shaded once per aligned 2x2 block
of guest pixels and the result written to all four, while VRAM keeps its
exact PS2 layout — one word per guest pixel — so everything that reads it
(textures sampled from drawn pages, framebuffer copies, readbacks) behaves
exactly as before. Only the drawn content loses sampling density. Uploads,
clears, copies and the present path are untouched and per-pixel exact.

Measured: the raster cost in a heavy fight window fell from 24-48 ms per
field to about 2.3 ms per field. The trade is visible and I will not hide it:
drawn content looks softer, a bit blockier. On a phone screen, in motion, I
take that trade every time.

### 3. Never let a small read drain the whole queue

This one was the rudest surprise. During fights, God Hand reads back tiny
pieces of what it just rendered — at most 64 pixels, at most 256 bytes at a
time — 15 to 20 times per window. Read synchronously, each of those reads
waits for *everything submitted before it* to finish: 175-285 ms per read,
seconds per fight, spent returning a quarter of a kilobyte. The read is not
expensive. The waiting is.

ARUS serves these reads from an asynchronous readback cache instead. The
first read of a given page, format, position and shape runs synchronously and
is captured; later reads of the same key are answered immediately from the
previous transfer's bytes while a refresh is queued in stream order behind
them. The guest never parks. The cache key includes the read's position, so
an entry can only ever serve bytes from the exact spot it was captured from.

The honest limit: served bytes are one transfer late. If a game reads pixels
to sample a slow-changing effect, nobody can tell. If a game makes a
same-frame decision from those pixels, it can act on where something *was*.
That risk is real, it is written down in the milestone report, and the switch
below turns the whole mechanism off if a game ever shows it.

### 4. Bound the queue in fields, not bytes

Even with the first three pillars, one structural problem remained: nothing
compared *fields produced* with *fields retired by the GPU*. The only
throttle in the submission path was a fence every 48 MB of upload stream —
and 48 MB, in a fight, is 25 to 70 fields of work. The screen was showing
frames the game had finished seconds ago, while newer finished frames queued
behind them. One frame per second on the display, over a game completing
fields ten times faster than that.

ARUS puts a fence gate at each field boundary on the GS worker: at field N,
the gate waits until field N-2's work has retired. At most two fields of
submitted work are ever unretired. Nothing is dropped — dropping GS work
would corrupt the game's own framebuffer bookkeeping, so ARUS never does —
the producer simply stops being allowed to run seconds ahead of the screen,
through the same queue that already bounded it before. The displayed frame's
age is printed in the log (it was 25-70 fields in fights; with the gate it is
at most three), because a fix you cannot see in the log is a story, not a fix.

## The one switch

All four mechanisms ship as one system with one master switch:

- `arus=off` in godhand.ini (or `PS2X_ARUS=off`) turns the whole system off
  and restores the pre-ARUS behaviour exactly: no dedup, native shading rate,
  the old readback gates, no queue gate. One setting, the old build.
- Each pillar also keeps its own switch, for isolating one mechanism at a
  time: `PS2X_GS_UPLOAD_DEDUP=0`, `PS2X_GS_RENDER_SCALE=1`,
  `PS2X_GS_ASYNC_READBACK=1` (or `off`), `PS2X_GS_QUEUE_DEPTH=off`.
- An explicit per-pillar setting in the environment outranks the master
  switch. The boot log prints the effective state of all four pillars on
  every run, so any test result can be tied to the configuration that
  produced it.

## What ARUS is not

It is not a promise of 60 FPS. Fights in God Hand still cost what they cost;
ARUS removes the *waiting* that was stacked on top of the work, and how much
that buys depends on how deep a given game's stalls were. Block shading makes
drawn content softer. Async readbacks answer one transfer late. The queue
gate means the emulated machine's field rate in a fight now honestly tracks
the GPU's retirement rate instead of a fictional one measured at the mouth
of a seconds-deep queue — a log will show *fewer* fields per second and a
game that *plays* faster, and both numbers are telling the truth.

## What ARUS is

One diagnosis, four mechanisms, one switch. A pipeline that assumes the
queue exists and is built around it: upload only what changed, shade only
what the screen can use, never wait synchronously for pocket change, and
never let finished work pile up seconds deep in front of the player.

God Hand (SLUS-21503, the player's own disc — ARUS ships no game data) is the
first game running it. The mechanisms are not God-Hand-specific; they live in
the GS backend, and any recompiled PS2 game on a mobile GPU queues the same
way this one did.

— ptraaa
