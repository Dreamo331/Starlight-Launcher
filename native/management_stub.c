/*
 * management_stub.c
 *
 * Provides JNI implementations for com.sun.management.internal.OperatingSystemImpl
 * native methods that Gluon substrate 0.0.69 does not resolve when building with
 * GraalVM Community Edition 17 (the methods become reachable via OSHI -> JMX ->
 * OperatingSystemMXBean, but the GraalVM CE static lib lacks these JNI symbols),
 * plus PDH-based disk throughput / memory sampling for SystemInfoMonitor
 * (OSHI/JNA is not usable inside a Native Image, so the Java side falls back
 * to these stubs).
 *
 * NOTE: this file is auto-compiled by Gluon substrate (project-root native/ dir).
 * The substrate compile step has no GraalVM include path, so jni.h is NOT used;
 * the minimal JNI types below are sufficient on x64 (undecorated symbol names).
 *
 * Memory values use the task-manager style "available" definition:
 *   free+zero+standby+modified lists (Available MBytes + Modified Page List Bytes).
 * CPU load is real (GetSystemTimes); disk throughput is real (PDH counters).
 *
 * PDH query is shared and guarded by a critical section: at most one collect per
 * second; all counters are cached and served from the cache between collects.
 */
#include <windows.h>

typedef struct JNIEnv_ JNIEnv;
typedef struct _jobject *jobject;
typedef long long jlong;
typedef double jdouble;

#define JNICALL
#define JNIEXPORT

/* ---------- linker keep-alive for JNI-dynamic disk symbols ---------- */
/* Substrate compiles application-class native methods to JNI dynamic lookups
 * (symbol resolved by name at runtime). The final exe links with /OPT:REF,
 * which drops unreferenced COMDATs: nothing references the disk stub symbols
 * statically, so they would be stripped and the runtime lookup would fail with
 * UnsatisfiedLinkError. /INCLUDE forces the linker to keep them, and the
 * volatile array adds a data-section reference as a second line of defense. */
JNIEXPORT __declspec(dllexport) jdouble JNICALL Java_com_example_starlight_util_SystemInfoMonitor_nativeDiskReadMBps(JNIEnv *env, jobject unused);
JNIEXPORT __declspec(dllexport) jdouble JNICALL Java_com_example_starlight_util_SystemInfoMonitor_nativeDiskWriteMBps(JNIEnv *env, jobject unused);

#pragma comment(linker, "/include:Java_com_example_starlight_util_SystemInfoMonitor_nativeDiskReadMBps")
#pragma comment(linker, "/include:Java_com_example_starlight_util_SystemInfoMonitor_nativeDiskWriteMBps")

static void *volatile s_diskKeepAlive[] = {
    (void *)&Java_com_example_starlight_util_SystemInfoMonitor_nativeDiskReadMBps,
    (void *)&Java_com_example_starlight_util_SystemInfoMonitor_nativeDiskWriteMBps
};

/* ---------- CPU load: GetSystemTimes (kernel+user vs idle deltas) ---------- */

static FILETIME s_lastIdle, s_lastKernel, s_lastUser;
static int s_hasCpuSample = 0;
static double s_cpuSmooth = -1.0; /* EWMA 平滑，对齐任务管理器观感（瞬时值跳动大） */

JNIEXPORT jdouble JNICALL Java_com_sun_management_internal_OperatingSystemImpl_getCpuLoad0
  (JNIEnv *env, jobject unused) {
    FILETIME idle, kernel, user;
    if (!GetSystemTimes(&idle, &kernel, &user)) {
        return -1.0;
    }
    if (!s_hasCpuSample) {
        s_lastIdle = idle;
        s_lastKernel = kernel;
        s_lastUser = user;
        s_hasCpuSample = 1;
        return -1.0; /* first call only establishes the baseline */
    }

    /* touch keep-alive refs so the disk stub symbols survive /OPT:REF */
    (void)s_diskKeepAlive[0];
    (void)s_diskKeepAlive[1];

    ULARGE_INTEGER i1, i2, k1, k2, u1, u2;
    i1.LowPart = s_lastIdle.dwLowDateTime;   i1.HighPart = s_lastIdle.dwHighDateTime;
    k1.LowPart = s_lastKernel.dwLowDateTime; k1.HighPart = s_lastKernel.dwHighDateTime;
    u1.LowPart = s_lastUser.dwLowDateTime;   u1.HighPart = s_lastUser.dwHighDateTime;
    i2.LowPart = idle.dwLowDateTime;         i2.HighPart = idle.dwHighDateTime;
    k2.LowPart = kernel.dwLowDateTime;       k2.HighPart = kernel.dwHighDateTime;
    u2.LowPart = user.dwLowDateTime;         u2.HighPart = user.dwHighDateTime;

    double idleDelta = (double)(i2.QuadPart - i1.QuadPart);
    double total = (double)(k2.QuadPart - k1.QuadPart)
                 + (double)(u2.QuadPart - u1.QuadPart);

    s_lastIdle = idle;
    s_lastKernel = kernel;
    s_lastUser = user;

    if (total <= 0.0) {
        return -1.0;
    }
    double load = 1.0 - idleDelta / total;
    if (load < 0.0) load = 0.0;
    if (load > 1.0) load = 1.0;
    if (s_cpuSmooth < 0.0) {
        s_cpuSmooth = load;
    } else {
        s_cpuSmooth = s_cpuSmooth * 0.7 + load * 0.3;
    }
    return s_cpuSmooth;
}

/* ---------- Memory: task-manager style available (PDH) with GMSE fallback ---------- */

typedef LONG (WINAPI *PdhOpenQueryFn)(LPCWSTR, DWORD_PTR, HANDLE *);
typedef LONG (WINAPI *PdhAddEnglishCounterFn)(HANDLE, LPCWSTR, DWORD_PTR, HANDLE *);
typedef LONG (WINAPI *PdhCollectQueryDataFn)(HANDLE);
typedef LONG (WINAPI *PdhGetFormattedCounterValueFn)(HANDLE, DWORD, LPDWORD, void *);

typedef struct {
    DWORD CStatus;
    union {
        LONG lValue;
        double doubleValue;
        LONGLONG largeValue;
    } data;
} PdhFmtCounterValue;

#define PDH_FMT_DOUBLE 0x00000200
#define PDH_FMT_LONG   0x00000100
#define PDH_FMT_LARGE  0x00000400

static HANDLE s_pdhQuery = NULL;
static HANDLE s_pdhRead = NULL;
static HANDLE s_pdhWrite = NULL;
static HANDLE s_pdhAvailMB = NULL;
static HANDLE s_pdhModified = NULL;
static PdhOpenQueryFn fPdhOpenQuery = NULL;
static PdhAddEnglishCounterFn fPdhAddEnglishCounter = NULL;
static PdhCollectQueryDataFn fPdhCollectQueryData = NULL;
static PdhGetFormattedCounterValueFn fPdhGetFormattedCounterValue = NULL;
static int s_pdhReady = 0;

static CRITICAL_SECTION s_pdhLock;
static volatile LONG s_pdhCsInit = 0;

/* cached results, refreshed together on each collect (>=1s apart) */
static ULONGLONG s_pdhLastCollectMs = 0;
static double s_pdhReadMBps = -1.0;
static double s_pdhWriteMBps = -1.0;
static double s_pdhAvailBytes = -1.0;   /* task-manager available: availMB+modified */
static double s_pdhTotalBytes = -1.0;

static int pdhInit(void) {
    if (s_pdhReady) return 1;
    if (InterlockedCompareExchange(&s_pdhCsInit, 1, 0) == 0) {
        InitializeCriticalSection(&s_pdhLock);
    }
    EnterCriticalSection(&s_pdhLock);
    if (s_pdhReady) { LeaveCriticalSection(&s_pdhLock); return 1; }

    HMODULE h = LoadLibraryW(L"pdh.dll");
    if (!h) {
        LeaveCriticalSection(&s_pdhLock);
        return 0;
    }
    fPdhOpenQuery = (PdhOpenQueryFn)GetProcAddress(h, "PdhOpenQueryW");
    fPdhAddEnglishCounter = (PdhAddEnglishCounterFn)GetProcAddress(h, "PdhAddEnglishCounterW");
    fPdhCollectQueryData = (PdhCollectQueryDataFn)GetProcAddress(h, "PdhCollectQueryData");
    fPdhGetFormattedCounterValue = (PdhGetFormattedCounterValueFn)GetProcAddress(h, "PdhGetFormattedCounterValue");
    if (!fPdhOpenQuery || !fPdhAddEnglishCounter || !fPdhCollectQueryData || !fPdhGetFormattedCounterValue) {
        LeaveCriticalSection(&s_pdhLock);
        return 0;
    }
    if (fPdhOpenQuery(NULL, 0, &s_pdhQuery) != 0) {
        LeaveCriticalSection(&s_pdhLock);
        return 0;
    }
    fPdhAddEnglishCounter(s_pdhQuery, L"\\PhysicalDisk(_Total)\\Disk Read Bytes/sec", 0, &s_pdhRead);
    fPdhAddEnglishCounter(s_pdhQuery, L"\\PhysicalDisk(_Total)\\Disk Write Bytes/sec", 0, &s_pdhWrite);
    fPdhAddEnglishCounter(s_pdhQuery, L"\\Memory\\Available MBytes", 0, &s_pdhAvailMB);
    fPdhAddEnglishCounter(s_pdhQuery, L"\\Memory\\Modified Page List Bytes", 0, &s_pdhModified);
    s_pdhReady = 1;
    LeaveCriticalSection(&s_pdhLock);
    return 1;
}

/* collect at most once per second; update all cached counters together */
static void pdhRefresh(void) {
    if (!pdhInit()) return;
    EnterCriticalSection(&s_pdhLock);
    ULONGLONG now = GetTickCount64();
    if (s_pdhLastCollectMs != 0 && (now - s_pdhLastCollectMs) < 1000) {
        LeaveCriticalSection(&s_pdhLock);
        return; /* cache is fresh */
    }
    if (fPdhCollectQueryData(s_pdhQuery) != 0) {
        LeaveCriticalSection(&s_pdhLock);
        return;
    }
    s_pdhLastCollectMs = now;

    PdhFmtCounterValue v;
    memset(&v, 0, sizeof(v));
    fPdhGetFormattedCounterValue(s_pdhRead, PDH_FMT_DOUBLE, NULL, &v);
    if (v.CStatus == 0) s_pdhReadMBps = v.data.doubleValue / (1024.0 * 1024.0);

    memset(&v, 0, sizeof(v));
    fPdhGetFormattedCounterValue(s_pdhWrite, PDH_FMT_DOUBLE, NULL, &v);
    if (v.CStatus == 0) s_pdhWriteMBps = v.data.doubleValue / (1024.0 * 1024.0);

    double availMB = -1.0;
    memset(&v, 0, sizeof(v));
    fPdhGetFormattedCounterValue(s_pdhAvailMB, PDH_FMT_LONG, NULL, &v);
    if (v.CStatus == 0) availMB = v.data.lValue;

    memset(&v, 0, sizeof(v));
    fPdhGetFormattedCounterValue(s_pdhModified, PDH_FMT_LARGE, NULL, &v);
    if (v.CStatus == 0 && availMB >= 0) {
        s_pdhAvailBytes = availMB * (1024.0 * 1024.0) + (double)v.data.largeValue;
    }
    LeaveCriticalSection(&s_pdhLock);
}

JNIEXPORT jlong JNICALL Java_com_sun_management_internal_OperatingSystemImpl_getTotalMemorySize0
  (JNIEnv *env, jobject unused) {
    MEMORYSTATUSEX ms;
    ms.dwLength = sizeof(ms);
    if (GlobalMemoryStatusEx(&ms)) {
        s_pdhTotalBytes = (double)ms.ullTotalPhys;
        return (jlong)ms.ullTotalPhys;
    }
    return 0;
}

JNIEXPORT jlong JNICALL Java_com_sun_management_internal_OperatingSystemImpl_getFreeMemorySize0
  (JNIEnv *env, jobject unused) {
    pdhRefresh();
    if (s_pdhAvailBytes >= 0 && s_pdhTotalBytes > 0) {
        jlong freeMem = (jlong)s_pdhAvailBytes;
        if (freeMem >= 0 && freeMem <= (jlong)s_pdhTotalBytes) {
            return freeMem; /* task-manager style available */
        }
    }
    /* fallback: GMSE (excludes modified list) */
    MEMORYSTATUSEX ms;
    ms.dwLength = sizeof(ms);
    if (GlobalMemoryStatusEx(&ms)) {
        return (jlong)ms.ullAvailPhys;
    }
    return 0;
}

JNIEXPORT void JNICALL Java_com_sun_management_internal_OperatingSystemImpl_initialize0
  (JNIEnv *env, jobject unused) {
}

/* ---------- Disk throughput: served from the shared PDH cache ---------- */
/* dllexport: substrate compiles application-class native methods to JNI dynamic
 * lookups, which resolve by scanning known libraries (GetModuleHandleA + GetProcAddress
 * on Windows, i.e. export tables). The Java side System.load()s the exe itself so
 * these exported symbols are findable. */

JNIEXPORT __declspec(dllexport) jdouble JNICALL Java_com_example_starlight_util_SystemInfoMonitor_nativeDiskReadMBps
  (JNIEnv *env, jobject unused) {
    pdhRefresh();
    return s_pdhReadMBps;
}

JNIEXPORT __declspec(dllexport) jdouble JNICALL Java_com_example_starlight_util_SystemInfoMonitor_nativeDiskWriteMBps
  (JNIEnv *env, jobject unused) {
    pdhRefresh();
    return s_pdhWriteMBps;
}
