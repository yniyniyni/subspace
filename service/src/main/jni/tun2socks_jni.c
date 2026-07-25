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

#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#include <android/log.h>

#include "hev-socks5-tunnel.h"

#define LOG_TAG "subspace-tun2socks"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Guards `running` and `worker` against a stop racing a start. Both can be
// driven from disconnect, onRevoke, and onDestroy (§5.4), which are not
// serialised with each other.
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_t worker;
static int running = 0;

struct start_args {
    unsigned char *config;
    unsigned int config_len;
    int tun_fd;
};

static void *
run_tunnel (void *arg)
{
    struct start_args *args = arg;

    // Blocks until hev_socks5_tunnel_quit(). Returns 0 on a clean stop.
    int rc = hev_socks5_tunnel_main_from_str (args->config, args->config_len,
                                              args->tun_fd);
    if (rc != 0) {
        // §5.6: the config carries the SOCKS port only — no addresses, no keys —
        // so the return code is safe to log. Never log the config itself.
        LOGE ("tunnel exited with %d", rc);
    }

    free (args->config);
    free (args);

    pthread_mutex_lock (&lock);
    running = 0;
    pthread_mutex_unlock (&lock);

    return NULL;
}

JNIEXPORT jboolean JNICALL
Java_art_yniyniyni_subspace_service_Tun2Socks_nativeStart (JNIEnv *env,
                                                           jclass clazz,
                                                           jstring config,
                                                           jint tun_fd)
{
    (void)clazz;

    // hev_socks5_tunnel_main_from_str ABORTS THE PROCESS on an invalid fd rather
    // than returning -1 — verified on device: passing -1 kills :bg outright.
    // The two-process split (§3) contains that blast radius, but a crash is still
    // a crash, and a start sequence must fail legibly (§10.4). Guard here, at the
    // boundary, so no Kotlin caller can trigger it.
    //
    // This cannot catch every bad fd — a closed but positive descriptor still
    // reaches the tunnel — so TunnelService must additionally treat
    // VpnService.Builder.establish() returning null as fatal and never call here.
    if (tun_fd < 0) {
        LOGE ("start refused: invalid tun fd %d", tun_fd);
        return JNI_FALSE;
    }

    pthread_mutex_lock (&lock);
    if (running) {
        pthread_mutex_unlock (&lock);
        LOGE ("start ignored: tunnel already running");
        return JNI_FALSE;
    }

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
    // JVM's string rather than referenced.
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

    pthread_mutex_lock (&lock);
    if (!running) {
        pthread_mutex_unlock (&lock);
        return;
    }
    pthread_t w = worker;
    pthread_mutex_unlock (&lock);

    // Unlocked across the join: run_tunnel takes the same mutex on its way out,
    // and holding it here would deadlock.
    hev_socks5_tunnel_quit ();
    pthread_join (w, NULL);

    LOGI ("tunnel stopped");
}

JNIEXPORT jboolean JNICALL
Java_art_yniyniyni_subspace_service_Tun2Socks_nativeIsRunning (JNIEnv *env,
                                                               jclass clazz)
{
    (void)env;
    (void)clazz;

    pthread_mutex_lock (&lock);
    int r = running;
    pthread_mutex_unlock (&lock);
    return r ? JNI_TRUE : JNI_FALSE;
}
