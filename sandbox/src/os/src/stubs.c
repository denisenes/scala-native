#include <stddef.h>
#include <stdint.h>

#define STUB(name) \
    uintptr_t name(void) { return 0; }


/* ---------- libc / POSIX ---------- */

STUB(access)
STUB(ceil)
STUB(close)
STUB(exit)
STUB(free)
STUB(getcwd)
STUB(geteuid)
STUB(getuid)
STUB(lseek)
STUB(malloc)
STUB(memcmp)
STUB(memcpy)
STUB(memmove)
STUB(memset)
STUB(printf)
STUB(read)
STUB(remove)
STUB(strchr)
STUB(strcpy)
STUB(strerror)
STUB(strncmp)
STUB(strncpy)
STUB(strrchr)
STUB(write)


/* ---------- Scala Native runtime ---------- */

STUB(scalanative_assignCurrentThread)

STUB(scalanative_atomic_exchange_llong)
STUB(scalanative_atomic_load_explicit_intptr)
STUB(scalanative_atomic_load_llong)
STUB(scalanative_atomic_memory_order_acquire)
STUB(scalanative_atomic_memory_order_release)
STUB(scalanative_atomic_memory_order_seq_cst)
STUB(scalanative_atomic_store_explicit_intptr)
STUB(scalanative_atomic_thread_fence)

STUB(scalanative_catch)
STUB(scalanative_continuation_init)

STUB(scalanative_currentNativeThread)
STUB(scalanative_currentThread)
STUB(scalanative_currentThreadInfo)

STUB(scalanative_current_time_millis)

STUB(scalanative_eacces)
STUB(scalanative_eexist)
STUB(scalanative_enoent)
STUB(scalanative_enotdir)
STUB(scalanative_enotempty)

STUB(scalanative_errno)

STUB(scalanative_fionread)
STUB(scalanative_f_ok)

STUB(scalanative_GC_alloc_array)
STUB(scalanative_GC_alloc_small)
STUB(scalanative_GC_init)

STUB(scalanative_getpwuid)
STUB(scalanative_get_vmoffset)

STUB(scalanative_ioctl)
STUB(scalanative_lstat)

STUB(scalanative_o_creat)
STUB(scalanative_open_m)
STUB(scalanative_o_rdonly)
STUB(scalanative_o_rdwr)

STUB(scalanative_page_size)

STUB(scalanative_personality)

STUB(scalanative_pthread_condattr_t_size)
STUB(scalanative_pthread_cond_t_size)
STUB(scalanative_pthread_mutexattr_t_size)
STUB(scalanative_pthread_mutex_t_size)

STUB(scalanative_seek_cur)
STUB(scalanative_seek_end)
STUB(scalanative_seek_set)

STUB(scalanative_set_os_props)

STUB(scalanative_setupCurrentThreadInfo)

STUB(scalanative_s_irgrp)
STUB(scalanative_s_iroth)
STUB(scalanative_s_irusr)

STUB(scalanative_s_islnk)

STUB(scalanative_s_iwgrp)
STUB(scalanative_s_iwoth)
STUB(scalanative_s_iwusr)

STUB(scalanative_StackOverflowGuards_check)
STUB(scalanative_StackOverflowGuards_close)
STUB(scalanative_StackOverflowGuards_reset)
STUB(scalanative_StackOverflowGuards_setup)
STUB(scalanative_StackOverflowGuards_size)

STUB(scalanative_stat)

STUB(scalanative_stderr_fileno)
STUB(scalanative_stdin_fileno)
STUB(scalanative_stdout_fileno)

STUB(scalanative_throw)

STUB(scalanative_Throwable_sizeOfExceptionWrapper)

STUB(scalanative_unwind_get_context)
STUB(scalanative_unwind_get_proc_name_by_ip)
STUB(scalanative_unwind_get_reg)
STUB(scalanative_unwind_init_local)
STUB(scalanative_unwind_sizeof_context)
STUB(scalanative_unwind_sizeof_cursor)
STUB(scalanative_unwind_step)
STUB(scalanative_unw_reg_ip)


/* ---------- Globals ---------- */

char **environ = NULL;



const char *snFatalErrorPrefix = NULL;