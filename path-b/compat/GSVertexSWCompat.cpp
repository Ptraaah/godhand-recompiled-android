// Path B compat (this project, GPL-3.0+): the shared vertex-conversion machinery
// from PCSX2's GSRendererSW.cpp, without the software rasterizer.
//
// Android path B does not build the SW renderer (GSRendererSW / GSRasterizer /
// GSDrawScanline and its code generators are excluded). Two things in that file
// are NOT the software rasterizer and are required by the hardware renderer:
//
//   1. GSVertexSW::InitStatic() and the ConvertVertexBuffer template table
//      (GSVertexSW::s_cvb) -- the HW renderer's vertex trace converts guest
//      vertices through this table. Copied verbatim from PCSX2 commit
//      2954ebe6965669219f1bbd256e958fbd8f83a85d, pcsx2/GS/Renderers/SW/GSRendererSW.cpp
//      lines 206-306 (SPDX-FileCopyrightText: 2002-2026 PCSX2 Dev Team,
//      SPDX-License-Identifier: GPL-3.0+).
//
//   2. isa_native::makeGSRendererSW() -- GS.cpp calls it when the software
//      renderer is selected. Path B has no software renderer, so this returns
//      the null renderer instead. Selecting GSRendererType::SW on path B is
//      therefore a deliberate no-picture mode, never a crash.
//
// Everything between the BEGIN/END markers is the verbatim upstream block.

#include "GS/Renderers/SW/GSRendererSW.h"
#include "GS/GSGL.h"
#include "GS/GSPng.h"
#include "GS/GSUtil.h"
#include "GS/Renderers/Null/GSRendererNull.h"

#include "common/StringUtil.h"

MULTI_ISA_UNSHARED_IMPL;

GSRenderer* CURRENT_ISA::makeGSRendererSW(int threads)
{
	// No software rasterizer in the Android path B build; see header comment.
	return new GSRendererNull();
}

// Also from upstream GSRendererSW.cpp (line 22, verbatim): the constant the
// vertex-conversion block below scales positions with.
static constexpr GSVector4 s_pos_scale = GSVector4::cxpr(1.0f / 16, 1.0f / 16, 1.0f, 128.0f);

// BEGIN verbatim upstream block (GSRendererSW.cpp lines 206-306 @ 2954ebe)
MULTI_ISA_DEF(void GSVertexSWInitStatic();)

#if MULTI_ISA_COMPILE_ONCE
GSVertexSW::ConvertVertexBufferPtr GSVertexSW::s_cvb[4][2][2][2];
void GSVertexSW::InitStatic()
{
	MULTI_ISA_SELECT(GSVertexSWInitStatic)();
}
#endif

MULTI_ISA_UNSHARED_START

template <u32 primclass, u32 tme, u32 fst, u32 q_div>
void ConvertVertexBuffer(const GSDrawingContext* RESTRICT ctx, GSVertexSW* RESTRICT dst, const GSVertex* RESTRICT src, u32 count)
{
	// FIXME q_div wasn't added to AVX2 code path.

	GSVector4i off = (GSVector4i)ctx->XYOFFSET;
	GSVector4 tsize = GSVector4(0x10000 << ctx->TEX0.TW, 0x10000 << ctx->TEX0.TH, 1, 0);
	GSVector4i z_max = GSVector4i::xffffffff().srl32(GSLocalMemory::m_psm[ctx->ZBUF.PSM].fmt * 8);

	for (int i = (int)count; i > 0; i--, src++, dst++)
	{
		GSVector4 stcq = GSVector4::load<true>(&src->m[0]); // s t rgba q

		GSVector4i xyzuvf(src->m[1]);

		GSVector4i xy = xyzuvf.upl16() - off;

		dst->c = GSVector4(GSVector4i::cast(stcq).zzzz().u8to32() << 7);

		GSVector4 t = GSVector4::zero();

		if (tme)
		{
			if (fst)
			{
				t = GSVector4(xyzuvf.uph16() << (16 - 4));
			}
			else if (q_div)
			{
				// Division is required if number are huge (Pro Soccer Club)
				if (primclass == GS_SPRITE_CLASS && (i & 1) == 0)
				{
					// q(n) isn't valid, you need to take q(n+1)
					const GSVertex* next = src + 1;
					GSVector4 stcq1 = GSVector4::load<true>(&next->m[0]); // s t rgba q
					t = (stcq / stcq1.wwww()) * tsize;
				}
				else
				{
					t = (stcq / stcq.wwww()) * tsize;
				}
			}
			else
			{
				t = stcq.xyww() * tsize;
			}
		}

		if (primclass == GS_SPRITE_CLASS)
		{
			dst->p = GSVector4(xy).xyyw(GSVector4(xyzuvf)) * s_pos_scale;

			xyzuvf = xyzuvf.min_u32(z_max);
			t = t.insert32<1, 3>(GSVector4::cast(xyzuvf));
		}
		else
		{
			double z = static_cast<double>(static_cast<u32>(xyzuvf.extract32<1>()));
			dst->p = (GSVector4(xy) * s_pos_scale).upld(GSVector4::f64(z, 0.0));
			t = t.blend32<8>(GSVector4(xyzuvf << 7));
		}

		dst->t = t;

#if 0 //_M_SSE >= 0x501

		dst->_pad = GSVector4::zero();

#endif
	}
}

void GSVertexSWInitStatic()
{
#define InitCVB4(P, T, F, Q) GSVertexSW::s_cvb[P][T][F][Q] = ConvertVertexBuffer<P, T, F, Q>;
#define InitCVB3(P, T, F) InitCVB4(P, T, F, 0) InitCVB4(P, T, F, 1)
#define InitCVB2(P, T) InitCVB3(P, T, 0) InitCVB3(P, T, 1)
#define InitCVB1(P) InitCVB2(P, 0) InitCVB2(P, 1)
	InitCVB1(GS_POINT_CLASS)
	InitCVB1(GS_LINE_CLASS)
	InitCVB1(GS_TRIANGLE_CLASS)
	InitCVB1(GS_SPRITE_CLASS)
#undef InitCVB1
#undef InitCVB2
#undef InitCVB3
#undef InitCVB4
}

MULTI_ISA_UNSHARED_END

// END verbatim upstream block
