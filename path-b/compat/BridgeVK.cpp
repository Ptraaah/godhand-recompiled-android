// libpcsx2gs.so (Android): the C interface of compat/pcsx2gs_api.h over PCSX2's GS,
// Vulkan renderer, surfaceless. Path B stage B1.
//
// This is compat/Bridge.cpp (the Windows/DX11 bridge of the same GS at the same
// upstream commit) with the platform parts replaced:
//   - WindowInfo::Type::Surfaceless instead of a Win32 child window; there is
//     no swap chain. The host (the game's GSPcsx2Backend) owns the Android
//     surface and presents pixels itself, so the composed frame is harvested
//     through the extra exports pcsx2gs_grab_frame()/pcsx2gs_grab_frame2()
//     below (GSRenderer::VSync merges DISPFB into the device's current
//     texture even without a swap chain). Since stage B3 the harvest is
//     pipelined: at each vsync the bridge records the snapshot (stretch +
//     download copy, the GPU steps of GSRenderer::SaveSnapshotToMemory)
//     into a ring of fence-tracked staging buffers and submits it without
//     waiting; a grab polls the fences and serves the freshest COMPLETED
//     capture, so the present path never blocks on a readback and lags the
//     GS by about one field. B1/B2 instead ran the synchronous snapshot per
//     grab (~6.6 ms of gs-fetch per present on the owner's POCO F5). The
//     API table and its version are unchanged; the grabs are separate
//     exports, the same pattern pcsx2gs_set_aspect already uses.
//   - GSRendererType::VK instead of DX11; GSDeviceVK feature paths are logged
//     after open (framebuffer fetch / texture barrier / ROV / stencil), which
//     is the B1 feature-negotiation evidence for the owner's driver.
//   - No DXGI present statistics, no Urban Reign game fixes (those were that
//     port's GameIndex entry; God Hand has none), no BMP snapshot file writer
//     (the grab export replaces it; Snapshot reports failure).
//   - Exports use default visibility instead of __declspec(dllexport).
//
// Threading, pre-pipeline (stages B1-B7): every entry point is called on the
// game's GS worker thread (the threaded backend serializes
// Open/Gif/Vsync/Present there), so the grab cache below needs no locking.
//
// Stage B8 (PATH-BRIDGE-PIPELINE.md): the base is first reconstructed to the
// stage-B3/B5 bridge the owner approved as pathb5 (pipelined capture ring,
// raw merged-texture serve; the stage-B6 canvas composition and the stage-B7
// open-time warm-up are removed again — they are later deltas, not part of
// that base). On top of it, the bridge gains its own GS thread, PCSX2's own
// MTGS arrangement: Gif/Vsync only enqueue into a bounded item queue
// (bounded in fields) and the bridge thread executes the GS in stream
// order, so the core's worker no longer executes the GS synchronously and
// the core's per-field queue drains stop serializing the game behind full
// GS execution of the backlog. Entry points whose result the caller
// consumes (readbacks, VRAM snapshots, resets) are fence items: executed in
// stream order on the bridge thread, waited on by the caller. The capture
// state is then written by the bridge thread and read by the grab caller,
// so it is guarded by g_captureMutex below. PS2X_GS_PIPELINE=0 restores the
// direct synchronous calls (the reconstructed base behaviour) in the same
// binary; PS2X_GS_PIPELINE_DEPTH=n sets the field bound (default 2).
//
// Harvest fastpath (PATH-PRESENT.md): with PS2X_GS_HARVEST_FASTPATH on
// (the default), the grab serves the freshest materialized capture
// directly from its ring slot — the serve cache keeps only metadata and
// the slot -> serve-cache copy is gone — and capture materialization
// (the staging -> slot copy) runs only on the bridge thread inside
// CaptureFrame; the grab path only checks fences. =0 restores the
// staged serve below verbatim.
#include "pcsx2gs_api.h"
#include "pcsx2gs_host.h"

#include "Config.h"
#include "GS/GS.h"
#include "GS/Renderers/Common/GSRenderer.h"
#include "GS/Renderers/Vulkan/GSDeviceVK.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <string_view>
#include <thread>
#include <vector>

extern Pcsx2Config EmuConfig;

namespace
{
    // The GS reads its privileged registers (PMODE, DISPFBn, CSR...) from this block, in the PS2 layout.
    alignas(16) u8 g_regs[0x2000] = {};
    // Atomic since stage B8: written by Open/Close (on the bridge thread when
    // the pipeline is on) and read by every entry point on its caller's thread.
    std::atomic<bool> g_open{false};
    WindowInfo g_window;

    // Present harvest state (stage B3, pipelined). At every vsync,
    // CaptureFrame() records a snapshot of the just-composed frame into the
    // next ring slot and submits the command buffer without waiting, so the
    // download runs concurrently with the next field's rendering. A grab
    // polls the slots (PollCapturesLocked()) and serves the freshest capture whose
    // fence has completed, staged in the g_grab_* serve cache below.
    struct CaptureSlot
    {
        GSTexture *target = nullptr;                 // stretch target (device pool texture, recycled at teardown/resize)
        std::unique_ptr<GSDownloadTexture> download; // staging buffer receiving the copy
        u32 width = 0;
        u32 height = 0;
        u64 fence = 0;        // device fence counter that completes the copy
        uint32_t vsync = 0;   // bridge vsync count this capture belongs to
        bool pending = false; // copy recorded, fence not yet observed complete
        bool fenceDone = false; // fence observed complete, pixels not yet copied out
        bool ready = false;   // pixels copied out to `pixels`
        std::vector<u32> pixels;
    };
    constexpr size_t kCaptureSlotCount = 3;
    CaptureSlot g_captures[kCaptureSlotCount];
    size_t g_capture_next = 0;
    uint32_t g_capture_dropped = 0; // captures skipped because their ring slot was still in flight (counted, never waited on)

    // Stage B8: guards the whole capture state (ring + serve cache). Held for
    // the full CaptureFrame (its GPU recording is submit-only; the one call
    // that can stall it, a device command-buffer rotation wait, merely makes
    // a grab serve the previous frame a little longer) and for every grab /
    // invalidate / destroy. Pre-pipeline this state had a single thread; the
    // mutex is uncontended in direct mode.
    std::mutex g_captureMutex;

    // The staged serve: the freshest completed capture, copied out once and
    // handed to every grab until a newer capture completes, so a grab's
    // size-then-copy call pair always sees the same frame.
    std::vector<u32> g_grab_pixels;
    u32 g_grab_width = 0;
    u32 g_grab_height = 0;
    uint32_t g_vsync_count = 0;
    uint32_t g_grab_vsync = 0;
    bool g_grab_valid = false;
    size_t g_grab_slot = 0; // fastpath only: the ring slot the g_grab_* metadata pins

    // Harvest fastpath gate (PATH-PRESENT.md §3): default on;
    // PS2X_GS_HARVEST_FASTPATH=0 restores the pre-fastpath serve verbatim
    // (serve-cache copy in RefreshServeLocked, grab-side materialization
    // through PollCapturesLocked).
    bool HarvestFastPathEnabled()
    {
        static const bool enabled = []
        {
            const char *value = std::getenv("PS2X_GS_HARVEST_FASTPATH");
            return !value || !*value || *value != '0';
        }();
        return enabled;
    }

    // Cheap fence check: marks every pending capture whose copy fence has
    // completed. No copies, no submits, no waits: the device's
    // command-buffer rotation keeps GetCompletedFenceCounter() current.
    // Any thread may run this under g_captureMutex; on the fastpath it is
    // all the grab path ever does about capture completion.
    void CheckCaptureFencesLocked()
    {
        GSDeviceVK *device = GSDeviceVK::GetInstance();
        if (!device)
            return;
        const u64 completed = device->GetCompletedFenceCounter();
        for (CaptureSlot &slot : g_captures)
            if (slot.pending && completed >= slot.fence)
                slot.fenceDone = true;
    }

    // Materialization: the staging -> slot.pixels copy for every capture
    // the fence check has marked complete. On the fastpath only
    // CaptureFrame (bridge thread) runs this; the pre-fastpath serve also
    // reaches it through PollCapturesLocked below. Caller holds
    // g_captureMutex.
    void MaterializeCapturesLocked()
    {
        for (CaptureSlot &slot : g_captures)
        {
            if (!slot.pending || !slot.fenceDone)
                continue;
            slot.pending = false;
            slot.fenceDone = false;
            // The fence already completed, so Flush() takes its early return
            // (no submit, no wait); Map() then only invalidates the host cache.
            slot.download->Flush();
            const GSVector4i rc(0, 0, static_cast<int>(slot.width), static_cast<int>(slot.height));
            if (!slot.download->Map(rc))
                continue;
            slot.pixels.resize(static_cast<size_t>(slot.width) * slot.height);
            const u8 *src = slot.download->GetMapPointer();
            const u32 pitch = slot.download->GetMapPitch();
            for (u32 y = 0; y < slot.height; ++y)
                std::memcpy(&slot.pixels[y * slot.width], src + static_cast<size_t>(y) * pitch,
                            slot.width * sizeof(u32));
            slot.ready = true;
        }
    }

    // Moves every capture whose copy fence has completed into its CPU pixel
    // buffer: the fence check and materialization composed, exactly the
    // pre-fastpath behaviour. Poll-only: this never submits and never
    // waits. Caller holds g_captureMutex.
    void PollCapturesLocked()
    {
        CheckCaptureFencesLocked();
        MaterializeCapturesLocked();
    }

    // Records a snapshot of the device's current (just-merged) texture into
    // the next ring slot and kicks the command buffer so the copy reaches
    // the GPU immediately. Same GPU steps as
    // GSRenderer::SaveSnapshotToMemory (stretch with the transparency
    // filter, then copy to a download texture), split so completion is
    // observed later by PollCapturesLocked() instead of waited on here.
    // The source rect is the whole texture: GSConfig.Crop is zero under the
    // bridge's options, so CalculateDrawSrcRect yields the full rect in
    // the synchronous snapshot too.
    //
    // Stage B8 reconstruction note: this is the stage-B3/B5 form again —
    // the snapshot target is the merged texture's own size and the raw
    // texture is what the harvest serves. The stage-B6 canvas composition
    // (an aspect-proportioned canvas between the texture and the serve)
    // and the stage-B7 open-time warm-up that pre-created this ring are
    // removed: both are post-b5 deltas, and the pipeline stage builds on
    // the b5 bridge the owner approved (see PATH-BRIDGE-PIPELINE.md).
    // The one addition to the B3 form is the capture mutex, held for the
    // whole recording: with the pipeline on, this runs on the bridge
    // thread while grabs poll the same state from the host thread.
    void CaptureFrame()
    {
        GSDeviceVK *device = GSDeviceVK::GetInstance();
        if (!device)
            return;
        GSTexture *current = device->GetCurrent();
        if (!current)
            return;
        std::lock_guard<std::mutex> lock(g_captureMutex);
        PollCapturesLocked();
        CaptureSlot &slot = g_captures[g_capture_next];
        if (slot.pending)
        {
            // The GPU is more than a full ring behind. Dropping this capture
            // keeps the GS thread non-blocking; the served frame simply
            // stays one field older for a step.
            ++g_capture_dropped;
            return;
        }
        const u32 width = static_cast<u32>(current->GetWidth());
        const u32 height = static_cast<u32>(current->GetHeight());
        if (slot.target && (slot.width != width || slot.height != height))
        {
            device->Recycle(slot.target);
            slot.target = nullptr;
            slot.download.reset();
        }
        if (!slot.target)
        {
            slot.target = device->CreateRenderTarget(static_cast<int>(width), static_cast<int>(height),
                                                     GSTexture::Format::Color, false);
            if (slot.target)
                slot.download = device->CreateDownloadTexture(width, height, GSTexture::Format::Color);
            if (!slot.target || !slot.download)
            {
                if (slot.target)
                {
                    device->Recycle(slot.target);
                    slot.target = nullptr;
                }
                slot.download.reset();
                return;
            }
            slot.width = width;
            slot.height = height;
        }
        const GSVector4i rc(0, 0, static_cast<int>(width), static_cast<int>(height));
        device->StretchRect(current, GSVector4(0.0f, 0.0f, 1.0f, 1.0f), slot.target, GSVector4(rc),
                            ShaderConvert::TRANSPARENCY_FILTER, Biln);
        slot.download->CopyFromTexture(rc, slot.target, rc, 0);
        slot.fence = device->GetCurrentFenceCounter();
        slot.vsync = g_vsync_count;
        slot.pending = true;
        slot.fenceDone = false;
        slot.ready = false;
        g_capture_next = (g_capture_next + 1) % kCaptureSlotCount;
        // Submit now, without waiting: the copy executes while the next
        // field is being built, instead of serializing into the next
        // vsync's BeginPresent submit.
        device->ExecuteCommandBuffer(false);
    }

    // Frees the ring's device resources. Must run while the device is alive
    // (before GSclose): the targets go back to the device pool and the
    // download destructors defer their buffers to the device.
    void DestroyCaptures()
    {
        std::lock_guard<std::mutex> lock(g_captureMutex);
        GSDeviceVK *device = GSDeviceVK::GetInstance();
        for (CaptureSlot &slot : g_captures)
        {
            slot.download.reset();
            if (slot.target && device)
                device->Recycle(slot.target);
            slot.target = nullptr;
            slot.pending = false;
            slot.fenceDone = false;
            slot.ready = false;
            slot.pixels.clear();
        }
        g_capture_next = 0;
    }

    // Drops all captured content without freeing resources (GS reset): the
    // host must not be served a frame from before the reset. In-flight
    // copies keep their buffers; the queue serializes them ahead of any
    // new copy recorded into the same slot, and the slot's fence is
    // rewritten when it is next initiated, so nothing stale is ever read.
    void InvalidateCaptures()
    {
        std::lock_guard<std::mutex> lock(g_captureMutex);
        for (CaptureSlot &slot : g_captures)
        {
            slot.pending = false;
            slot.fenceDone = false;
            slot.ready = false;
        }
        g_grab_valid = false;
        g_grab_vsync = 0;
    }

    // Stages the freshest completed capture into the serve cache, if newer
    // than what is staged. Returns the staged frame's vsync counter, or 0
    // when no capture has completed yet. Caller holds g_captureMutex.
    uint32_t RefreshServeLocked()
    {
        PollCapturesLocked();
        const CaptureSlot *best = nullptr;
        for (const CaptureSlot &slot : g_captures)
            if (slot.ready && (!best || slot.vsync > best->vsync))
                best = &slot;
        if (best && (!g_grab_valid || best->vsync != g_grab_vsync))
        {
            g_grab_pixels = best->pixels;
            g_grab_width = best->width;
            g_grab_height = best->height;
            g_grab_vsync = best->vsync;
            g_grab_valid = true;
        }
        return g_grab_valid ? g_grab_vsync : 0;
    }

    // Fastpath serve staging: pins the freshest materialized capture by
    // ring index and keeps only its metadata in the g_grab_* state — the
    // pixels stay in the slot and the copy call reads them there, so the
    // slot -> serve-cache copy above is skipped. Only a strictly newer
    // capture re-pins; when nothing newer has materialized, the previous
    // pin keeps serving (the fastpath twin of the staged cache serving
    // the previous frame), and when nothing has materialized at all the
    // not-ready case is RefreshServeLocked's: 0. Caller holds
    // g_captureMutex.
    uint32_t RefreshServeFastLocked()
    {
        CheckCaptureFencesLocked();
        const CaptureSlot *best = nullptr;
        size_t bestIndex = 0;
        for (size_t i = 0; i < kCaptureSlotCount; ++i)
        {
            const CaptureSlot &slot = g_captures[i];
            if (slot.ready && (!best || slot.vsync > best->vsync))
            {
                best = &slot;
                bestIndex = i;
            }
        }
        if (best && (!g_grab_valid || best->vsync > g_grab_vsync))
        {
            g_grab_slot = bestIndex;
            g_grab_width = best->width;
            g_grab_height = best->height;
            g_grab_vsync = best->vsync;
            g_grab_valid = true;
        }
        return g_grab_valid ? g_grab_vsync : 0;
    }

    // Pcsx2GsOpenParams::options: "key=value;key=value". Same keys as the DX bridge.
    void ApplyOptions(Pcsx2Config::GSOptions &options, const char *text)
    {
        if (!text)
            return;
        std::string_view rest(text);
        while (!rest.empty())
        {
            const size_t end = rest.find(';');
            const std::string_view item = rest.substr(0, end);
            rest = end == std::string_view::npos ? std::string_view() : rest.substr(end + 1);
            const size_t eq = item.find('=');
            if (eq == std::string_view::npos)
                continue;
            const std::string_view key = item.substr(0, eq);
            const std::string text = std::string(item.substr(eq + 1));
            const int value = std::atoi(text.c_str());
            if (key == "texture_dir")
            {
                // Replacement textures (PNG/DDS in <dir>/replacements), loaded as the game uses them.
                // Path B has no PNG/DDS loaders compiled in, so this stays inert on Android; kept for
                // parity with the DX bridge's option surface.
                pcsx2gs::SetTextureFolder(text);
                options.LoadTextureReplacements = !text.empty();
                options.LoadTextureReplacementsAsync = true;
                continue;
            }
            const auto clampU8 = [](int v, int lo, int hi) { return static_cast<u8>(std::clamp(v, lo, hi)); };
            if (key == "fxaa")
                options.FXAA = value != 0;
            else if (key == "texture_filtering")
                options.TextureFiltering = static_cast<BiFiltering>(std::clamp(value, 0, 3));
            else if (key == "trilinear")
                options.TriFilter = static_cast<TriFiltering>(std::clamp(value, -1, 2));
            else if (key == "anisotropy")
                options.MaxAnisotropy = clampU8(value, 0, 16);
            else if (key == "cas")
                options.CASMode = static_cast<GSCASMode>(std::clamp(value, 0, 2));
            else if (key == "cas_sharpness")
                options.CAS_Sharpness = clampU8(value, 0, 100);
            else if (key == "shadeboost")
                options.ShadeBoost = value != 0;
            else if (key == "brightness")
                options.ShadeBoost_Brightness = clampU8(value, 1, 100);
            else if (key == "contrast")
                options.ShadeBoost_Contrast = clampU8(value, 1, 100);
            else if (key == "saturation")
                options.ShadeBoost_Saturation = clampU8(value, 1, 100);
            else if (key == "gamma")
                options.ShadeBoost_Gamma = clampU8(value, 1, 100);
            else if (key == "tv_shader")
                options.TVShader = clampU8(value, 0, 7);
            else if (key == "present_filter")
                options.LinearPresent = static_cast<GSPostBilinearMode>(std::clamp(value, 0, 2));
            else if (key == "dithering")
                options.Dithering = clampU8(value, 0, 3);
            else if (key == "texture_precache")
                options.PrecacheTextureReplacements = value != 0;
            else if (key == "blending")
                options.AccurateBlendingUnit = static_cast<AccBlendLevel>(std::clamp(value, 0, 5));
            // M39 (godhand docs/M39-CORE-REPORT.md): the hardware download mode, previously
            // unreachable from the port (GSOptions kept its Enabled/accurate default, so every
            // renderer-internal framebuffer download stayed synchronous). Values are
            // GSHardwareDownloadMode: 0 Enabled, 1 EnabledForceFull, 2 NoReadbacks,
            // 3 Unsynchronized, 4 Disabled. The port ships 2 (NoReadbacks).
            else if (key == "hw_download")
                options.HWDownloadMode = static_cast<GSHardwareDownloadMode>(std::clamp(value, 0, 4));
            // M39: texture preloading level (0 Off, 1 Partial, 2 Full). GSOptions already
            // defaults to Full; the key exists so the resolved value is explicit in the
            // open line's options string.
            else if (key == "texture_preloading")
                options.TexturePreloading = static_cast<TexturePreloadingLevel>(std::clamp(value, 0, 2));
        }
    }

    int OpenDirect(const Pcsx2GsOpenParams *params)
    {
        if (g_open || !params)
            return 0;
        g_window = {};
        g_window.type = WindowInfo::Type::Surfaceless;
        g_window.surface_width = std::max(1u, params->width);
        g_window.surface_height = std::max(1u, params->height);
        pcsx2gs::SetRenderWindow(g_window);
        pcsx2gs::SetFolders(params->resources ? params->resources : "", params->cache ? params->cache : "");

        Pcsx2Config::GSOptions options;
        options.Renderer = GSRendererType::VK;
        options.UpscaleMultiplier = std::max(1.0f, params->scale);
        options.AspectRatio = params->stretch ? AspectRatioType::Stretch : AspectRatioType::R4_3;
        ApplyOptions(options, params->options);
        EmuConfig.GS = options;
        // The presentation reads the current aspect ratio (PCSX2's VMManager copies it from GS.AspectRatio at boot).
        EmuConfig.CurrentAspectRatio = options.AspectRatio;

        const GSVSyncMode vsync = params->vsync == 1u ? GSVSyncMode::FIFO
                                  : params->vsync == 2u ? GSVSyncMode::Mailbox
                                                        : GSVSyncMode::Disabled;
        if (!GSopen(options, GSRendererType::VK, g_regs, vsync, false))
        {
            std::fprintf(stderr, "[pcsx2-gs] GSopen failed\n");
            return 0;
        }
        g_open = true;
        g_grab_valid = false;
        g_grab_vsync = 0;
        g_vsync_count = 0;
        g_capture_dropped = 0;
        InvalidateCaptures();
        int w = 0, h = 0;
        GSgetInternalResolution(&w, &h);
        std::fprintf(stderr, "[pcsx2-gs] Vulkan surfaceless, scale %.0f (%dx%d), surface %ux%u, options: %s\n", options.UpscaleMultiplier, w, h,
                     g_window.surface_width, g_window.surface_height, params->options ? params->options : "");
        std::fprintf(stderr, "[pcsx2-gs] harvest fastpath: %s (PS2X_GS_HARVEST_FASTPATH=0 restores the staged serve)\n",
                     HarvestFastPathEnabled() ? "on" : "off");
        // Which renderer paths the feature negotiation picked on this driver.
        if (GSDeviceVK *device = GSDeviceVK::GetInstance())
        {
            // M44: name the GPU in every successful boot log, so a report
            // from any phone identifies its hardware without a follow-up
            // question. The properties are already inside the device (no
            // extra Vulkan call); this prints only on the open-success
            // path — on an open failure the port's own [gs] fallback line
            // is the record.
            const VkPhysicalDeviceProperties &props = device->GetDeviceProperties();
            std::fprintf(stderr, "[pcsx2-gs] Vulkan device: %s (vendor 0x%04x, device 0x%04x, Vulkan %u.%u.%u)\n",
                         props.deviceName, props.vendorID, props.deviceID,
                         VK_VERSION_MAJOR(props.apiVersion), VK_VERSION_MINOR(props.apiVersion),
                         VK_VERSION_PATCH(props.apiVersion));
            const GSDevice::FeatureSupport f = device->Features();
            const GSDeviceVK::OptionalExtensions &oe = device->GetOptionalExtensions();
            std::fprintf(stderr,
                         "[pcsx2-gs] VK features: framebuffer_fetch=%d texture_barrier=%d feedback_loops=%d rov=%d stencil=%d "
                         "provoking_vertex=%d dxt=%d bptc=%d exts: feedback_loop_layout=%d rasterization_order=%d fragment_interlock=%d synchronization2=%d\n",
                         f.framebuffer_fetch ? 1 : 0, f.texture_barrier ? 1 : 0, f.feedback_loops() ? 1 : 0, f.rov ? 1 : 0,
                         f.stencil_buffer ? 1 : 0, f.provoking_vertex_last ? 1 : 0, f.dxt_textures ? 1 : 0, f.bptc_textures ? 1 : 0,
                         oe.vk_ext_attachment_feedback_loop_layout ? 1 : 0, oe.vk_ext_rasterization_order_attachment_access ? 1 : 0,
                         oe.vk_ext_fragment_shader_interlock ? 1 : 0, oe.vk_khr_synchronization2 ? 1 : 0);
        }
        // Stage B8: the stage-B7 open-time warm-up that used to run here is
        // removed with the rest of the post-b5 bridge deltas (see the
        // CaptureFrame reconstruction note); the capture ring is again
        // created lazily at the first vsyncs, as in the b5 bridge.
        return 1;
    }

    void CloseDirect()
    {
        if (!g_open)
            return;
        // The ring's resources belong to the device GSclose() is about to
        // destroy; release them first.
        DestroyCaptures();
        GSclose();
        g_open = false;
        std::lock_guard<std::mutex> lock(g_captureMutex);
        g_grab_valid = false;
    }

    void GifDirect(const uint8_t *data, uint32_t qwc)
    {
        if (g_open && data && qwc)
            GSgifTransfer(data, qwc);
    }

    void VsyncDirect(const Pcsx2GsPrivRegs *regs)
    {
        if (!g_open || !regs)
            return;
        for (u32 i = 0; i < 15u; ++i)
            std::memcpy(g_regs + i * 0x10u, &regs->regset1[i], 8);
        std::memcpy(g_regs + 0x1000, &regs->csr, 8);
        std::memcpy(g_regs + 0x1010, &regs->imr, 8);
        std::memcpy(g_regs + 0x1040, &regs->busdir, 8);
        std::memcpy(g_regs + 0x1080, &regs->siglblid, 8);
        // As PCSX2's MTGS: CSR.FIELD (bit 13) picks the field.
        GSvsync((regs->csr & 0x2000u) ? 0u : 1u, true);
        ++g_vsync_count;
        // GSvsync just merged this field into the device's current texture;
        // start its pipelined snapshot now (see CaptureFrame).
        CaptureFrame();
    }

    void ResetDirect()
    {
        if (g_open)
        {
            GSreset(false);
            InvalidateCaptures();
        }
    }

    void ReadFifoDirect(uint8_t *dst, uint32_t qwc)
    {
        if (g_open && dst && qwc)
            GSInitAndReadFIFO(dst, qwc);
    }

    void ResizeDirect(uint32_t width, uint32_t height)
    {
        if (!g_open || width == 0u || height == 0u)
            return;
        g_window.surface_width = width;
        g_window.surface_height = height;
        pcsx2gs::SetRenderWindow(g_window);
        // Surfaceless there is no swap chain to recreate; GSDeviceVK::ResizeWindow
        // early-returns without one. Kept for API parity with the DX bridge.
        GSResizeDisplayWindow(width, height, 1.0f);
    }

    void SetScaleDirect(float scale)
    {
        if (!g_open)
            return;
        Pcsx2Config::GSOptions options = GSConfig;
        options.UpscaleMultiplier = std::max(1.0f, scale);
        EmuConfig.GS = options;
        GSUpdateConfig(options);
    }

    // As GSState::Defrost: a hardware reset (the HW renderer empties its texture cache and targets), then the memory.
    void LoadVramDirect(const uint8_t *data, uint32_t size)
    {
        if (!g_open || !data || !g_gs_renderer)
            return;
        g_gs_renderer->Reset(true);
        std::memcpy(g_gs_renderer->m_mem.m_vm8, data, std::min<size_t>(size, g_gs_renderer->m_mem.m_vmsize));
    }

    uint32_t SaveVramDirect(uint8_t *dst, uint32_t size)
    {
        if (!g_open || !dst || !g_gs_renderer)
            return 0;
        g_gs_renderer->Flush(GSState::GSFlushReason::SAVESTATE);
        g_gs_renderer->ReadbackTextureCache();
        const uint32_t bytes = std::min<uint32_t>(size, g_gs_renderer->m_mem.m_vmsize);
        std::memcpy(dst, g_gs_renderer->m_mem.m_vm8, bytes);
        return bytes;
    }

    // As GSState::InitReadFIFO, for an arbitrary transfer and leaving m_env/m_tr alone.
    uint32_t ReadLocalDirect(uint8_t *dst, uint32_t size, uint64_t bitbltbuf, uint64_t trxpos, uint64_t trxreg)
    {
        if (!g_open || !dst || !size || !g_gs_renderer)
            return 0;
        GIFRegBITBLTBUF blit{bitbltbuf};
        GIFRegTRXPOS pos{trxpos};
        GIFRegTRXREG reg{trxreg};
        g_gs_renderer->Flush(GSState::DOWNLOADFIFO);
        const GSVector4i rect(pos.SSAX, pos.SSAY, pos.SSAX + reg.RRW, pos.SSAY + reg.RRH);
        g_gs_renderer->InvalidateLocalMem(blit, rect);
        int x = pos.SSAX, y = pos.SSAY;
        g_gs_renderer->m_mem.ReadImageX(x, y, dst, static_cast<int>(size), blit, pos, reg);
        return size;
    }

    int SnapshotDirect(const char *)
    {
        // No file writer in path B (no PNG/BMP encoder compiled in); the host
        // harvests frames through pcsx2gs_grab_frame instead.
        return 0;
    }

    // ------------------------------------------------------------------
    // Stage B8: the bridge pipeline (see the threading note at the top of
    // this file and PATH-BRIDGE-PIPELINE.md). One bridge thread executes
    // every GS call in stream order; producers only enqueue. Vsync items
    // are bounded in fields: a producer parks only while the pipeline
    // already holds PS2X_GS_PIPELINE_DEPTH uncompleted fields, which
    // replaces the old arrangement where the core's queue drains
    // serialized the game behind full GS execution of the entire queued
    // backlog, twice per field on the present path. Fence items (anything
    // whose result the caller consumes, plus open/close/reset) execute in
    // stream order on the bridge thread and signal their caller.
    // ------------------------------------------------------------------

    bool PipelineEnabled()
    {
        static const bool enabled = []
        {
            const char *value = std::getenv("PS2X_GS_PIPELINE");
            return !value || !*value || *value != '0';
        }();
        return enabled;
    }

    uint32_t PipelineDepth()
    {
        static const uint32_t depth = []
        {
            const char *value = std::getenv("PS2X_GS_PIPELINE_DEPTH");
            const int parsed = (value && *value) ? std::atoi(value) : 2;
            return static_cast<uint32_t>(std::clamp(parsed, 1, 8));
        }();
        return depth;
    }

    struct PipeFence
    {
        std::mutex mutex;
        std::condition_variable cv;
        bool done = false;

        void Signal()
        {
            {
                std::lock_guard<std::mutex> lock(mutex);
                done = true;
            }
            cv.notify_all();
        }

        void Wait()
        {
            std::unique_lock<std::mutex> lock(mutex);
            cv.wait(lock, [&] { return done; });
        }
    };

    struct PipeItem
    {
        std::function<void()> run;        // executed on the bridge thread, in queue order
        std::shared_ptr<PipeFence> fence; // set: the poster waits for completion
        bool isVsync = false;             // counts against the field-depth bound
        bool isClose = false;             // the bridge loop returns after this item
    };

    std::mutex g_pipeMutex;
    std::condition_variable g_pipeCv; // signalled on post, on vsync completion, on stop
    std::deque<PipeItem> g_pipeQueue;
    uint32_t g_pipeFieldsAhead = 0; // vsyncs posted, not yet completed (under g_pipeMutex)
    uint32_t g_pipeAheadMax = 0;    // window high-water of the above (under g_pipeMutex)
    bool g_pipeStop = false;        // under g_pipeMutex
    std::thread g_pipeThread;
    uint64_t g_pipeVsyncsDone = 0; // bridge thread only
    std::atomic<uint64_t> g_pipeParkNs{0};
    std::atomic<uint64_t> g_pipeParkCount{0};

    void PipeThreadMain()
    {
        for (;;)
        {
            PipeItem item;
            {
                std::unique_lock<std::mutex> lock(g_pipeMutex);
                g_pipeCv.wait(lock, [] { return g_pipeStop || !g_pipeQueue.empty(); });
                if (g_pipeQueue.empty())
                    return; // stop requested and the queue is drained
                item = std::move(g_pipeQueue.front());
                g_pipeQueue.pop_front();
            }
            if (item.run)
                item.run();
            if (item.isVsync)
            {
                uint32_t aheadMax = 0;
                {
                    std::lock_guard<std::mutex> lock(g_pipeMutex);
                    if (g_pipeFieldsAhead)
                        --g_pipeFieldsAhead;
                    aheadMax = g_pipeAheadMax;
                    g_pipeAheadMax = g_pipeFieldsAhead;
                }
                g_pipeCv.notify_all();
                // The pipeline's own report card, on roughly the core's ~5 s
                // window cadence (300 fields at the guest's field rate): how
                // deep the queue ran and how long producers spent parked on
                // the depth bound — the only producer park the pipeline
                // keeps. Park counters are per-window (exchanged out here).
                if (++g_pipeVsyncsDone % 300u == 0u)
                {
                    std::fprintf(stderr,
                                 "[pathb] pipeline: fields=%llu ahead_max=%u park=%.1f ms (%llu waits) dropped_captures=%u depth=%u\n",
                                 static_cast<unsigned long long>(g_pipeVsyncsDone), aheadMax,
                                 static_cast<double>(g_pipeParkNs.exchange(0)) / 1e6,
                                 static_cast<unsigned long long>(g_pipeParkCount.exchange(0)),
                                 g_capture_dropped, PipelineDepth());
                    std::fflush(stderr);
                }
            }
            if (item.fence)
                item.fence->Signal();
            if (item.isClose)
                return;
        }
    }

    void PipeStart()
    {
        if (g_pipeThread.joinable())
            return;
        {
            std::lock_guard<std::mutex> lock(g_pipeMutex);
            g_pipeStop = false;
            g_pipeFieldsAhead = 0;
            g_pipeAheadMax = 0;
        }
        g_pipeVsyncsDone = 0;
        g_pipeThread = std::thread(PipeThreadMain);
    }

    // Stops the bridge thread. The loop only leaves with an empty queue
    // (or after a close item), so nothing posted is ever dropped silently
    // in normal operation.
    void PipeStop()
    {
        {
            std::lock_guard<std::mutex> lock(g_pipeMutex);
            g_pipeStop = true;
        }
        g_pipeCv.notify_all();
        if (g_pipeThread.joinable())
            g_pipeThread.join();
    }

    void PipePost(PipeItem &&item)
    {
        {
            std::lock_guard<std::mutex> lock(g_pipeMutex);
            if (g_pipeStop)
                return; // shutting down; only reachable from a raced post
            g_pipeQueue.push_back(std::move(item));
        }
        g_pipeCv.notify_all();
    }

    // Posts a vsync item, first parking while the pipeline already holds a
    // full depth of uncompleted fields. This bounded park is the whole of
    // the backpressure the pipeline applies to producers.
    void PipePostVsync(PipeItem &&item)
    {
        const auto t0 = std::chrono::steady_clock::now();
        bool waited = false;
        {
            std::unique_lock<std::mutex> lock(g_pipeMutex);
            if (!g_pipeStop && g_pipeFieldsAhead >= PipelineDepth())
            {
                waited = true;
                g_pipeCv.wait(lock, [] { return g_pipeStop || g_pipeFieldsAhead < PipelineDepth(); });
            }
            if (g_pipeStop)
                return; // shutting down; the field goes with the session
            ++g_pipeFieldsAhead;
            if (g_pipeFieldsAhead > g_pipeAheadMax)
                g_pipeAheadMax = g_pipeFieldsAhead;
            g_pipeQueue.push_back(std::move(item));
        }
        g_pipeCv.notify_all();
        if (waited)
        {
            g_pipeParkNs.fetch_add(static_cast<uint64_t>(
                std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - t0).count()));
            g_pipeParkCount.fetch_add(1);
        }
    }

    // Posts a fence item and waits for the bridge thread to complete it.
    // Falls back to running the call inline when no bridge thread is alive,
    // which can only happen around a failed open or a teardown race — where
    // the direct call is the pre-pipeline behaviour anyway.
    void PipeCall(std::function<void()> fn)
    {
        if (!g_pipeThread.joinable())
        {
            fn();
            return;
        }
        auto fence = std::make_shared<PipeFence>();
        bool queued = false;
        {
            std::lock_guard<std::mutex> lock(g_pipeMutex);
            if (!g_pipeStop)
            {
                g_pipeQueue.push_back(PipeItem{std::move(fn), fence, false, false});
                queued = true;
            }
        }
        if (!queued)
        {
            fn();
            return;
        }
        g_pipeCv.notify_all();
        fence->Wait();
    }

    void SetAspectDirect(uint32_t mode)
    {
        EmuConfig.CurrentAspectRatio = mode == 2u ? AspectRatioType::Stretch : mode == 1u ? AspectRatioType::R16_9
                                                                                          : AspectRatioType::R4_3;
    }

    // ------------------------------------------------------------------
    // Entry points (the API table below points here). Each one either runs
    // its Direct twin on the caller's thread (pipeline off: the b5
    // behaviour, unchanged) or routes it through the bridge queue.
    // ------------------------------------------------------------------

    int Open(const Pcsx2GsOpenParams *params)
    {
        if (!PipelineEnabled())
            return OpenDirect(params);
        const bool alreadyOpen = g_open.load();
        PipeStart();
        int result = 0;
        PipeCall([&] { result = OpenDirect(params); });
        if (result)
            std::fprintf(stderr,
                         "[pcsx2-gs] pipeline on: the GS runs on the bridge thread, depth %u fields "
                         "(PS2X_GS_PIPELINE=0 restores direct calls)\n",
                         PipelineDepth());
        else if (!alreadyOpen)
            PipeStop();
        return result;
    }

    void Close()
    {
        if (!PipelineEnabled() || !g_pipeThread.joinable())
        {
            CloseDirect();
            return;
        }
        // GSclose must run on the bridge thread, after everything queued:
        // the close item is last in stream order and ends the loop.
        auto fence = std::make_shared<PipeFence>();
        {
            std::lock_guard<std::mutex> lock(g_pipeMutex);
            g_pipeQueue.push_back(PipeItem{[] { CloseDirect(); }, fence, false, true});
        }
        g_pipeCv.notify_all();
        fence->Wait();
        PipeStop();
    }

    void Gif(const uint8_t *data, uint32_t qwc)
    {
        if (!PipelineEnabled())
        {
            GifDirect(data, qwc);
            return;
        }
        if (!g_open || !data || !qwc)
            return;
        // The caller's buffer is only valid for the duration of the call
        // (PCSX2's MTGS copies into its ring buffer for the same reason).
        std::vector<u8> copy(data, data + static_cast<size_t>(qwc) * 16u);
        PipeItem item;
        item.run = [copy = std::move(copy)] { GifDirect(copy.data(), static_cast<uint32_t>(copy.size() / 16u)); };
        PipePost(std::move(item));
    }

    void Vsync(const Pcsx2GsPrivRegs *regs)
    {
        if (!PipelineEnabled())
        {
            VsyncDirect(regs);
            return;
        }
        if (!g_open || !regs)
            return;
        const Pcsx2GsPrivRegs copy = *regs;
        PipeItem item;
        item.isVsync = true;
        item.run = [copy] { VsyncDirect(&copy); };
        PipePostVsync(std::move(item));
    }

    void Reset()
    {
        if (!PipelineEnabled())
        {
            ResetDirect();
            return;
        }
        if (!g_open)
            return;
        PipeCall([] { ResetDirect(); });
    }

    void ReadFifo(uint8_t *dst, uint32_t qwc)
    {
        if (!PipelineEnabled())
        {
            ReadFifoDirect(dst, qwc);
            return;
        }
        if (!g_open || !dst || !qwc)
            return;
        // A true readback — the guest consumes these bytes, so this stays a
        // hard sync by design. What changes is what it waits behind: the
        // stream ahead of it on the bridge thread, in order, instead of the
        // core worker first chewing through every chunk it had queued.
        PipeCall([dst, qwc] { ReadFifoDirect(dst, qwc); });
    }

    void Resize(uint32_t width, uint32_t height)
    {
        if (!PipelineEnabled())
        {
            ResizeDirect(width, height);
            return;
        }
        if (!g_open || width == 0u || height == 0u)
            return;
        PipeItem item;
        item.run = [width, height] { ResizeDirect(width, height); };
        PipePost(std::move(item));
    }

    void SetScale(float scale)
    {
        if (!PipelineEnabled())
        {
            SetScaleDirect(scale);
            return;
        }
        if (!g_open)
            return;
        PipeItem item;
        item.run = [scale] { SetScaleDirect(scale); };
        PipePost(std::move(item));
    }

    void LoadVram(const uint8_t *data, uint32_t size)
    {
        if (!PipelineEnabled())
        {
            LoadVramDirect(data, size);
            return;
        }
        if (!g_open || !data)
            return;
        // The caller waits on the fence, so its buffer outlives the call.
        PipeCall([data, size] { LoadVramDirect(data, size); });
    }

    uint32_t SaveVram(uint8_t *dst, uint32_t size)
    {
        if (!PipelineEnabled())
            return SaveVramDirect(dst, size);
        if (!g_open || !dst)
            return 0;
        uint32_t result = 0;
        PipeCall([&] { result = SaveVramDirect(dst, size); });
        return result;
    }

    uint32_t ReadLocal(uint8_t *dst, uint32_t size, uint64_t bitbltbuf, uint64_t trxpos, uint64_t trxreg)
    {
        if (!PipelineEnabled())
            return ReadLocalDirect(dst, size, bitbltbuf, trxpos, trxreg);
        if (!g_open || !dst || !size)
            return 0;
        uint32_t result = 0;
        PipeCall([&] { result = ReadLocalDirect(dst, size, bitbltbuf, trxpos, trxreg); });
        return result;
    }

    int Snapshot(const char *path)
    {
        if (!PipelineEnabled())
            return SnapshotDirect(path);
        int result = 0;
        PipeCall([&] { result = SnapshotDirect(path); });
        return result;
    }

    const Pcsx2GsApi g_api = {PCSX2GS_API_VERSION, Open, Close, Gif, Vsync, Reset, ReadFifo, Resize, SetScale, LoadVram, SaveVram, ReadLocal, pcsx2gs::SetOsd, Snapshot, pcsx2gs::SetMenu, pcsx2gs::MenuInput};
}

extern "C" __attribute__((visibility("default"))) const Pcsx2GsApi *pcsx2gs_get_api(uint32_t version)
{
    return version == PCSX2GS_API_VERSION ? &g_api : nullptr;
}

// The aspect ratio while the game runs (0 = 4:3, 1 = 16:9, 2 = fill the window), read at every present: the port uses
// it to follow the game's own 4:3 / 16:9 option. Exported on its own so that the API table above stays the same.
extern "C" __attribute__((visibility("default"))) void pcsx2gs_set_aspect(uint32_t mode)
{
    // Stage B8: with the pipeline on, the aspect is GS state like any other —
    // it is applied on the bridge thread, in stream order, instead of being
    // written from the caller's thread across the queue.
    if (PipelineEnabled() && g_open && g_pipeThread.joinable())
    {
        PipeItem item;
        item.run = [mode] { SetAspectDirect(mode); };
        PipePost(std::move(item));
        return;
    }
    SetAspectDirect(mode);
}

// Present harvest for the surfaceless build (path B extra, not part of the API
// table). Returns the composed frame as tightly packed RGBA8, row 0 = top.
//
// Stage B3 pipelined form: the frame served is the freshest capture whose
// download fence has completed (captures are initiated per vsync, see
// CaptureFrame), so a grab never submits or waits — it polls and copies.
// The served frame lags the GS by about one field. Call pattern, as with
// the B1/B2 synchronous form: a size query (dst == nullptr) refreshes the
// staged frame and reports the needed byte count; the copy call that
// follows serves exactly that staged frame. Returns 0 when no capture has
// completed yet.
//
// Harvest fastpath form (default; PS2X_GS_HARVEST_FASTPATH=0 restores the
// staged serve above): the size query pins the freshest materialized
// capture's slot and metadata, the copy call memcpys out of that slot,
// and materialization happens only inside CaptureFrame on the bridge
// thread — the grab side never copies staging into a slot itself.
extern "C" __attribute__((visibility("default"))) uint32_t pcsx2gs_grab_frame2(uint8_t *dst, uint32_t capacity, uint32_t *width, uint32_t *height, uint32_t *counter)
{
    if (!g_open)
        return 0;
    // Stage B8: the capture state is written by the bridge thread now; the
    // whole serve (refresh + read + copy) runs under the capture mutex.
    std::lock_guard<std::mutex> lock(g_captureMutex);
    if (HarvestFastPathEnabled())
    {
        // Fastpath serve: the copy call reads the pinned slot in place;
        // no serve-cache copy, and materialization stays on the bridge
        // thread (the refresh above only checks fences).
        if (!dst)
            RefreshServeFastLocked();
        if (!g_grab_valid)
            return 0;
        // The pin stays truthful while the slot is untouched (ready,
        // same vsync) or merely re-recorded (new vsync, not ready): a
        // slot's pixels are only overwritten by materializing a newer
        // capture into it, which flips it back to ready under its new
        // vsync in the same locked step. Anything else means the pinned
        // frame's pixels are gone; re-pin to the freshest materialized
        // capture and serve that instead.
        const CaptureSlot &pinned = g_captures[g_grab_slot];
        const bool pinIntact = (pinned.vsync == g_grab_vsync) ? pinned.ready : !pinned.ready;
        if (!pinIntact)
        {
            RefreshServeFastLocked();
            if (!g_grab_valid)
                return 0;
            const CaptureSlot &repinned = g_captures[g_grab_slot];
            if (!repinned.ready || repinned.vsync != g_grab_vsync)
                return 0;
        }
        const CaptureSlot &slot = g_captures[g_grab_slot];
        const uint32_t bytes = g_grab_width * g_grab_height * 4u;
        if (width)
            *width = g_grab_width;
        if (height)
            *height = g_grab_height;
        if (counter)
            *counter = g_grab_vsync;
        if (dst && capacity >= bytes)
            std::memcpy(dst, slot.pixels.data(), bytes);
        return bytes;
    }
    if (!dst)
        RefreshServeLocked();
    if (!g_grab_valid)
        return 0;
    const uint32_t bytes = g_grab_width * g_grab_height * 4u;
    if (width)
        *width = g_grab_width;
    if (height)
        *height = g_grab_height;
    if (counter)
        *counter = g_grab_vsync;
    if (dst && capacity >= bytes)
        std::memcpy(dst, g_grab_pixels.data(), bytes);
    return bytes;
}

// The B1 grab export, kept for the backend's fallback path: identical serve,
// without the frame counter.
extern "C" __attribute__((visibility("default"))) uint32_t pcsx2gs_grab_frame(uint8_t *dst, uint32_t capacity, uint32_t *width, uint32_t *height)
{
    return pcsx2gs_grab_frame2(dst, capacity, width, height, nullptr);
}
