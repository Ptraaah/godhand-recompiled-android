// Path B compat (this project, GPL-3.0+): shm_open/shm_unlink for Android,
// backed by memfd_create.
//
// Bionic does not provide the POSIX shared-memory API in this NDK's public
// headers. Both upstream callers use the same pattern -- create a uniquely
// named object with O_CREAT|O_EXCL, unlink the name immediately, ftruncate
// and mmap the descriptor -- which is exactly what an anonymous memfd is.
// memfd_create is invoked through the raw syscall so it works regardless of
// the NDK's API-level gating (the syscall exists on every kernel >= 3.17;
// the POCO F5 runs a 5.x kernel). shm_unlink is a no-op: a memfd has no name
// to remove, and both callers only unlink names they just created.
//
// Caveat for B1: this makes the code compile and gives the GS's
// wrapped-memory allocation a real backing store, but the allocation path
// itself (GSAllocateWrappedMemory's repeated MAP_FIXED mapping trick over
// one descriptor) still has to be exercised on a device before it counts as
// proven.

#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <cerrno>

#ifndef SYS_memfd_create
#if defined(__aarch64__)
#define SYS_memfd_create 279
#else
#error "SYS_memfd_create not defined for this architecture"
#endif
#endif

extern "C" int shm_open(const char* name, int oflag, mode_t mode)
{
	(void)oflag;
	(void)mode;
	const long fd = syscall(SYS_memfd_create, (name && name[0]) ? name : "pcsx2gs", 0);
	if (fd < 0)
		return -1;
	return static_cast<int>(fd);
}

extern "C" int shm_unlink(const char* name)
{
	(void)name;
	return 0;
}
