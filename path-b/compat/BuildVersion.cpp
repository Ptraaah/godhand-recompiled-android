// Path B compat (this project, GPL-3.0+): BuildVersion constants for the
// extracted PCSX2 GS. Upstream generates these from git at build time
// (pcsx2/BuildVersion.cpp + a generated svnrev.h). The extraction is a
// source snapshot, not a git checkout, so the values are stated plainly:
// untagged, with the pinned upstream commit hash they were extracted from.
// GSDeviceVK only uses GitTagHi/Mid/Lo to compose a version number; zeros
// are the honest value for an untagged snapshot.

#include "BuildVersion.h"

namespace BuildVersion
{
	const char* GitTag = "";
	bool GitTaggedCommit = false;
	int GitTagHi = 0;
	int GitTagMid = 0;
	int GitTagLo = 0;
	const char* GitRev = "";
	const char* GitHash = "2954ebe6965669219f1bbd256e958fbd8f83a85d";
	const char* GitDate = "2026-10-11";
} // namespace BuildVersion
