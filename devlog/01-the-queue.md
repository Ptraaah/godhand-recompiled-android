# Why my PS2 recompile ran at 1 FPS on a fast phone


---

I recompile PS2 games so they run natively on Android phones. The game code gets translated ahead of time into a native ARM64 program, so it is not an emulator in the usual sense. Right now the project is God Hand, and for a long time it had a bug that made no sense.

Menus: 60 fps. Fights: 1 or 2 fps. Same phone, same build.

The phone is a POCO F5, which is not slow, and the translated code was not the slow part either. We had profiles. CPU was fine. So I burned a lot of time suspecting everything except the thing that was actually guilty.

The queue. It was the queue the whole time.

A PS2 graphics chip does not queue anything. It sits right next to its memory and does what it is told, when it is told. Upload, draw, read, done, all more or less instant. A phone GPU is built the other way. It batches work, holds it, finishes it whenever it finishes it. If the line gets long, your frame from two seconds ago is still in it.

Now take a renderer written in the PS2 style and run it on that. Every time the renderer waits for something, a fence, a buffer, a read coming back, it does not wait for that one thing. It waits for everything in line in front of it. Whole queue, every time. That was the bug, and honestly it was three or four bugs wearing one coat, because the stalls stacked.

First one was uploads. The game re-sends the same textures constantly. Same sprites, same animation frames, over and over, and the original pipeline happily dispatched every single one into VRAM again. So now the pipeline hashes each upload and skips it if the destination memory already holds the exact same bytes. In fights that skips around 30 percent of the upload traffic. It helped. The fights still stalled, which is how I knew it was not the main problem.

Second one was raster cost. Filling a PS2 frame pixel by pixel is work the original chip did in dedicated hardware, and on a phone GPU at native resolution it is just a lot. The fix I landed on shades drawn content once per 2x2 block of pixels and writes that result to all four. VRAM keeps the exact PS2 layout, one word per pixel, so anything reading that memory later sees no difference at all. Heavy fight windows went from 24 to 48 ms of raster per field down to about 2.3 ms. Trade-off is real and I am not hiding it: drawn stuff looks softer, a bit blocky. On a phone screen while things are moving, I will take that deal every single time.

Third one was embarrassing once I saw it. Nothing anywhere compared how many fields the game had produced with how many the GPU had actually retired. The only brake in the whole submission path was a fence every 48 MB of uploads, and 48 MB during a fight is somewhere between 25 and 70 fields of work. The screen was showing frames the game had finished seconds ago while newer finished frames waited in line behind them. So now there is a gate at every field boundary. Field N does not start until field N minus 2 has retired. Never more than two fields outstanding, nothing dropped, the producer just is not allowed to sprint seconds ahead of the screen anymore. The log prints how old the displayed frame is. It used to say 25 to 70 fields. Now it says at most 3.

The last stall is still being worked on as I write this, and it is the rudest one. During fights the game reads back tiny scraps of what it just drew. At most 64 pixels. 256 bytes. Fifteen to twenty times per window. Done the normal synchronous way, every one of those little reads waits for the entire queue in front of it, so the phone spends 175 to 285 ms returning a quarter of a kilobyte. Per fight that adds up to seconds of pure waiting. The read was never expensive. The waiting was the expensive part.

Half the fix for that already ships. Those reads get answered from an async cache now, previous transfer's bytes, refresh queued behind, and the cache key carries the exact position so an entry can only ever serve the spot it was captured from. The limit is written down in my own notes and I will write it here too: the bytes are one transfer late. If the game is sampling some slow effect, nobody can tell. If it ever makes a same-frame decision from those pixels, it could act on stale data, and that is why the whole thing has an off switch.

The other half is on the bench right now. Turns out the fight reads walk. Same row, one pixel to the right, again, again, again, and the first read at each new spot is the one that misses and pays the full queue drain. So the pipeline now spots the walk, grabs the next sixteen positions in one go instead of sixteen separate trips, and a miss inside the walk gets served by its nearest cached neighbour while the real bytes backfill quietly behind it. Does it work? The build logs the counters, hits, misses, wasted prefetches, all of it. I will post what the phone says either way, good or bad.

I packaged the four mechanisms as one system with one master switch, because a pile of unrelated tweaks is not something you can think about clearly. I call it ARUS. Indonesian word, means current, flow. Upload what changed. Shade what the screen can use. Do not park the whole machine over pocket change. Do not let finished frames pile up seconds deep in front of the player.

It is not a 60 fps promise. God Hand was not a locked 60 game on real hardware either. ARUS just removes the waiting that was stacked on top of the actual work, and the mechanisms live in the graphics backend, not in God Hand's code, so the next recompiled game that queues like this gets the same fixes.

The project ships no game data, players bring their own disc. If low level stuff like this is your thing, the work is public on my GitHub and the coffee fund is open.

ptraaa
