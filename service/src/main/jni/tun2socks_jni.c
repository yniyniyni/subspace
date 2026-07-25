// SPDX-License-Identifier: AGPL-3.0-or-later
//
// JNI bridge to hev-socks5-tunnel 2.16.0 (MIT). See THIRD_PARTY.md.
//
// The tunnel's entry point blocks until quit is called, so it runs on its own
// pthread and the Kotlin side gets non-blocking start/stop.
//
// ARCHITECTURE.md §10.2: this looks like boilerplate and is load-bearing. It is
// the hop that turns raw IP packets off the TUN fd into SOCKS connections
// against the loopback inbound (§3, packet path). Do not refactor it for
// elegance; change one thing at a time and verify on device.
//
// ── Locking contract ────────────────────────────────────────────────────────
//
// `lock` protects `running`, `worker`, and `worker_valid`. The worker thread
// NEVER takes `lock`, which is what makes it safe to hold across pthread_join.
// The worker's only cross-thread signal is the atomic `worker_finished`.
//
// An earlier revision let the worker clear `running` under the lock, which
// forced nativeStop to drop the lock around quit()+join to avoid deadlocking.
// That was a real bug, not a style question: §5.4 says disconnect, onRevoke,
// and onDestroy are not serialised with each other, so two teardowns could both
// observe running==1, both call hev_socks5_tunnel_quit(), and both join one tid.
// The second quit() never returns — upstream's hev_socks5_tunnel_stop busy-waits
// for event_fds[1] to become valid, and the first stop already closed it. The
// result is a teardown thread spinning at 10 Hz forever with the TUN fd still
// open, which is precisely the wedged-until-reboot state §5.4 exists to prevent.

#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

#include <android/log.h>

#include "hev-socks5-tunnel.h"

#define LOG_TAG "subspace-tun2socks"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;

// Guarded by `lock`.
static pthread_t worker;
static int worker_valid = 0;
static int running = 0;

// Written only by the worker, read by anyone. Not guarded by `lock` — the whole
// point is that the worker can publish its exit without taking a lock that
// nativeStop may be holding while joining it.
static atomic_int worker_finished = 0;

struct start_args {
    unsigned char *config;
    unsigned int config_len;
    int tun_fd;
};

static void *
run_tunnel (void *arg)
{
    struct start_args *args = arg;

    // Blocks until hev_socks5_tunnel_quit(), or returns early on a startup
    // failure (bad config, logger init, task system, tunnel init).
    int rc = hev_socks5_tunnel_main_from_str (args->config, args->config_len,
                                              args->tun_fd);
    if (rc != 0) {
        // §5.6: the tunnel config carries only the loopback SOCKS port — no
        // addresses, no keys — so the return code is safe to log. Never log the
        // config itself.
        LOGE ("tunnel exited with %d", rc);
    }

    free (args->config);
    free (args);

    atomic_store (&worker_finished, 1);
    return NULL;
}

// Reaps a worker that exited on its own. Caller must hold `lock`.
//
// Without this, a tunnel that fails at startup leaves a joinable thread nobody
// joins: nativeStop early-returns because `running` was already cleared, and the
// next nativeStart overwrites `worker`, losing the tid. Each failed connect
// would strand a thread stack (1 MB by default) inside :bg — a user retrying a
// bad server would leak steadily.
static void
reap_locked (void)
{
    if (worker_valid) {
        pthread_join (worker, NULL);
        worker_valid = 0;
    }
    running = 0;
    atomic_store (&worker_finished, 0);
}

JNIEXPORT jboolean JNICALL
Java_art_yniyniyni_subspace_service_Tun2Socks_nativeStart (JNIEnv *env,
                                                           jclass clazz,
                                                           jstring config,
                                                           jint tun_fd)
{
    (void)clazz;

    // hev_socks5_tunnel_main_from_str ABORTS THE PROCESS on an invalid fd
    // rather than returning -1 — observed on device: passing -1 killed the test
    // runner outright. The two-process split (§3) is why that is survivable in
    // production, but a start sequence must fail legibly (§10.4), so this is
    // refused at the boundary.
    //
    // This cannot catch a closed-but-positive descriptor, so TunnelService must
    // additionally treat VpnService.Builder.establish() returning null as fatal
    // and never call in.
    if (tun_fd < 0) {
        LOGE ("start refused: invalid tun fd %d", tun_fd);
        return JNI_FALSE;
    }

    pthread_mutex_lock (&lock);

    if (running && !atomic_load (&worker_finished)) {
        pthread_mutex_unlock (&lock);
        LOGE ("start ignored: tunnel already running");
        return JNI_FALSE;
    }

    // Either idle, or a previous run exited on its own and left a corpse.
    reap_locked ();

    const char *cfg = (*env)->GetStringUTFChars (env, config, NULL);
    if (!cfg) {
        pthread_mutex_unlock (&lock);
        LOGE ("start failed: could not read config");
        return JNI_FALSE;
    }

    struct start_args *args = malloc (sizeof (struct start_args));
    if (!args) {
        (*env)->ReleaseStringUTFChars (env, config, cfg);
        pthread_mutex_unlock (&lock);
        LOGE ("start failed: out of memory");
        return JNI_FALSE;
    }

    // The worker outlives this JNI frame, so the config is copied out of the
    // JVM's string. hev_config_init_from_str takes an explicit length, so the
    // buffer does not need a NUL terminator.
    args->config_len = (unsigned int)strlen (cfg);
    args->config = malloc (args->config_len);
    if (!args->config) {
        free (args);
        (*env)->ReleaseStringUTFChars (env, config, cfg);
        pthread_mutex_unlock (&lock);
        LOGE ("start failed: out of memory");
        return JNI_FALSE;
    }
    memcpy (args->config, cfg, args->config_len);
    args->tun_fd = tun_fd;
    (*env)->ReleaseStringUTFChars (env, config, cfg);

    if (pthread_create (&worker, NULL, run_tunnel, args) != 0) {
        free (args->config);
        free (args);
        pthread_mutex_unlock (&lock);
        LOGE ("start failed: could not create worker thread");
        return JNI_FALSE;
    }

    worker_valid = 1;
    running = 1;
    pthread_mutex_unlock (&lock);
    LOGI ("tunnel started");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_art_yniyniyni_subspace_service_Tun2Socks_nativeStop (JNIEnv *env,
                                                          jclass clazz)
{
    (void)env;
    (void)clazz;

    // Held for the whole function. Safe because the worker never takes it, and
    // required because §5.4's three teardown paths can arrive concurrently —
    // see the locking contract at the top of this file.
    pthread_mutex_lock (&lock);

    if (!running) {
        // Nothing live, but a self-exited worker may still need reaping.
        reap_locked ();
        pthread_mutex_unlock (&lock);
        return;
    }

    // Skip quit() when the worker has already returned. Calling it then would
    // busy-wait forever on an event fd that has already been torn down.
    //
    // Residual window, deliberately accepted: if the worker is mid-startup and
    // is going to fail before upstream's event_task_init runs, quit() can spin.
    // It is bounded in every other case. TunnelService must not call stop()
    // before the state machine has observed a started tunnel.
    if (!atomic_load (&worker_finished)) {
        hev_socks5_tunnel_quit ();
    }

    reap_locked ();
    pthread_mutex_unlock (&lock);

    LOGI ("tunnel stopped");
}

JNIEXPORT jboolean JNICALL
Java_art_yniyniyni_subspace_service_Tun2Socks_nativeIsRunning (JNIEnv *env,
                                                               jclass clazz)
{
    (void)env;
    (void)clazz;

    pthread_mutex_lock (&lock);
    // A worker that exited on its own is not running, even though `running` is
    // still set until someone reaps it.
    int r = running && !atomic_load (&worker_finished);
    pthread_mutex_unlock (&lock);
    return r ? JNI_TRUE : JNI_FALSE;
}
