// Path B compat (this project, GPL-3.0+): texture-replacement loaders, stubbed.
//
// Upstream implements these two entry points in
// pcsx2/GS/Renderers/HW/GSTextureReplacementLoaders.cpp on top of libpng (and
// the Windows port used WIC). Path B has no PNG codec yet, and texture
// replacements are an optional user-content feature that is off unless the
// user both enables it and supplies replacement files. Stubbing the loaders
// makes the feature report "nothing loadable", which is exactly the behaviour
// of a build whose replacement folder is empty:
//
//   GetLoader()   -> nullptr (no loader for any extension)
//   SavePNGImage() -> false  (dumping replaceable textures writes nothing)
//
// Porting a real PNG loader (e.g. through the NDK's libpng in the full app
// build) is a B1+ nicety, not a B0 requirement.

#include "GS/Renderers/HW/GSTextureReplacements.h"

GSTextureReplacements::ReplacementTextureLoader GSTextureReplacements::GetLoader(const std::string_view filename)
{
	return nullptr;
}

bool GSTextureReplacements::SavePNGImage(const std::string& filename, u32 width, u32 height, const u8* buffer, u32 pitch)
{
	return false;
}
