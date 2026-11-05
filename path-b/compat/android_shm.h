// Path B compat (this project, GPL-3.0+): shm_open/shm_unlink declarations for
// Android. Bionic's public headers in this NDK do not declare the POSIX
// shared-memory functions at all, but two upstream files use them
// (common/Linux/LnxHostSys.cpp for HostSys shared memory, pcsx2/GS/GS.cpp for
// the GS's wrapped-memory allocation). This header is force-included into
// those two translation units only (see CMakeLists.txt); the implementation
// in AndroidShm.cpp is backed by memfd_create. Do not include this header
// anywhere else.
#pragma once

#include <sys/types.h>
#include <sys/stat.h>

#ifdef __cplusplus
extern "C" {
#endif

int shm_open(const char* name, int oflag, mode_t mode);
int shm_unlink(const char* name);

#ifdef __cplusplus
}
#endif
