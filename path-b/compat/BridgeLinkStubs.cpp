// Path B stage B1: link closure for libpcsx2gs.so.
//
// Same closure the B1-prep vktest proved on the owner's device (see
// vktest-app/jni/link_stubs.cpp). Pulling GS.cpp into the link drags the GS
// closure with it, and exactly eight symbols stay undefined:
//
//   - Six belong to the software renderer (GSSingleRasterizer,
//     GSRasterizerData::s_counter, GSTextureCacheSW::Texture), which path B
//     excludes on purpose (see PATH-B-B0.md). Their only references are
//     GSRendererHWMultiISA.cpp's SwPrimRender path. That path is
//     master-gated by GSConfig.UserHacks_CPUSpriteRenderBW, whose upstream
//     default is 0 = disabled (pcsx2/Config.h), and the bridge never enables
//     it, so these paths cannot execute in path B. The symbols below are
//     inert placeholders that exist only to satisfy the linker; they must
//     never be called. The real fix, if path B ever wants CPU sprite render,
//     is to build the SW objects, not to grow these placeholders.
//
//   - Two are real one-function helpers the DX port never needed, defined
//     here with their upstream semantics:
//       * Pcsx2Config::GSOptions::TriStateToOptionalBoolean (upstream
//         pcsx2/Pcsx2Config.cpp): -1 (unset, e.g. ExclusiveFullscreenControl)
//         maps to nullopt, 0 to false, anything else to true.
//       * Host::ReportFormattedErrorAsync (upstream pcsx2/Host.cpp):
//         printf-format the message and forward to Host::ReportErrorAsync,
//         which compat/HostStubs.cpp already provides.

#include "Config.h"
#include "Host.h"

#include <cstdarg>
#include <cstdio>
#include <optional>

std::optional<bool> Pcsx2Config::GSOptions::TriStateToOptionalBoolean(int value)
{
	return (value == -1) ? std::nullopt : std::optional<bool>(value != 0);
}

namespace Host
{
	void ReportFormattedErrorAsync(const std::string_view title, const char* format, ...)
	{
		char buf[1024];
		va_list ap;
		va_start(ap, format);
		std::vsnprintf(buf, sizeof(buf), format, ap);
		va_end(ap);
		ReportErrorAsync(title, buf);
	}
} // namespace Host

// --- inert placeholders for the excluded software renderer (see header) ---
extern "C"
{
	__attribute__((visibility("default"))) void pathb_stub_swrasterizer_ctor1()
		__asm__("_ZN10isa_native18GSSingleRasterizerC1Ev");
	void pathb_stub_swrasterizer_ctor1() {}

	__attribute__((visibility("default"))) void pathb_stub_swrasterizer_ctor2()
		__asm__("_ZN10isa_native18GSSingleRasterizerC2Ev");
	void pathb_stub_swrasterizer_ctor2() {}

	__attribute__((visibility("default"))) void pathb_stub_swrasterizer_draw()
		__asm__("_ZN10isa_native18GSSingleRasterizer4DrawERNS_16GSRasterizerDataE");
	void pathb_stub_swrasterizer_draw() {}

	__attribute__((visibility("default"))) void pathb_stub_texcache_ctor1()
		__asm__("_ZN16GSTextureCacheSW7TextureC1EjRK10GIFRegTEX0RK10GIFRegTEXA");
	void pathb_stub_texcache_ctor1() {}

	__attribute__((visibility("default"))) void pathb_stub_texcache_ctor2()
		__asm__("_ZN16GSTextureCacheSW7TextureC2EjRK10GIFRegTEX0RK10GIFRegTEXA");
	void pathb_stub_texcache_ctor2() {}

	__attribute__((visibility("default"))) void pathb_stub_texcache_reset()
		__asm__("_ZN16GSTextureCacheSW7Texture5ResetEjRK10GIFRegTEX0RK10GIFRegTEXA");
	void pathb_stub_texcache_reset() {}

	__attribute__((visibility("default"))) void pathb_stub_texcache_update()
		__asm__("_ZN16GSTextureCacheSW7Texture6UpdateERK10GSVector4i");
	void pathb_stub_texcache_update() {}
}

// GSRasterizerData::s_counter (a plain static int upstream).
__asm__(".bss\n"
		".globl _ZN10isa_native16GSRasterizerData9s_counterE\n"
		".type _ZN10isa_native16GSRasterizerData9s_counterE, %object\n"
		".align 4\n"
		"_ZN10isa_native16GSRasterizerData9s_counterE:\n"
		".zero 4\n"
		".size _ZN10isa_native16GSRasterizerData9s_counterE, 4\n");
