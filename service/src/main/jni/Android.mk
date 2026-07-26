# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Builds the tun2socks JNI bridge with hev-socks5-tunnel 2.16.0 (MIT) compiled
# straight in, producing a single libtun2socks.so.
#
# We do NOT include upstream's own Android.mk, for one specific reason: it
# compiles src/hev-jni.c, which carries a JNI_OnLoad that calls RegisterNatives
# against upstream's own app class, hev/htproxy/TProxyService. Loading that
# library from any other package aborts the process:
#
#   JNI DETECTED ERROR IN APPLICATION: JNI RegisterNatives called with pending
#   exception java.lang.ClassNotFoundException: Didn't find class
#   "hev.htproxy.TProxyService"
#
# hev-jni.c can be retargeted with -DPKGNAME/-DCLSNAME, and its API was
# considered. It was rejected because TProxyStartService returns void: it cannot
# report that the tunnel failed to start, and ARCHITECTURE.md §10.4 is explicit
# that a start sequence which swallows failure produces the worst state this app
# can reach — UI says connected, nothing works, no log line. Our shim returns a
# boolean and guards the fd instead.
#
# Everything else upstream builds is used unmodified; only that one file is
# filtered out.
#
# §10.2: this bridge is load-bearing, not boilerplate. Do not refactor it for
# elegance.

SUBSPACE_JNI_PATH := $(call my-dir)
HEV_DIR := $(SUBSPACE_JNI_PATH)/../../../../third_party/hev-socks5-tunnel
HEV_REL := ../../../../third_party/hev-socks5-tunnel

# Upstream's three vendored static dependencies, built by their own makefiles.
include $(HEV_DIR)/third-part/yaml/Android.mk
include $(HEV_DIR)/third-part/lwip/Android.mk
include $(HEV_DIR)/third-part/hev-task-system/Android.mk

# build.mk populates SRCFILES by globbing SRCDIR. Reusing it rather than listing
# sources by hand means a file added upstream is picked up on the next bump.
SRCDIR := $(HEV_DIR)/src
include $(HEV_DIR)/build.mk
# The exclusion below is a safety property, not a tidy-up: without it, every
# process loading this library aborts. It is a literal filename match against a
# glob whose whole purpose is to pick up whatever upstream adds, so if a
# submodule bump renames or splits that file the filter would quietly match
# nothing, JNI_OnLoad would compile back in, and THE BUILD WOULD STAY GREEN.
# That is §10.1 in one line. Fail at build time instead.
ifeq ($(filter %/hev-jni.c,$(SRCFILES)),)
$(error hev-socks5-tunnel no longer ships src/hev-jni.c - re-verify the JNI_OnLoad exclusion in service/src/main/jni/Android.mk)
endif
HEV_SRCFILES := $(filter-out %/hev-jni.c,$(SRCFILES))

LOCAL_PATH := $(SUBSPACE_JNI_PATH)

include $(CLEAR_VARS)
LOCAL_MODULE := tun2socks
LOCAL_SRC_FILES := \
    tun2socks_jni.c \
    $(patsubst $(HEV_DIR)/%,$(HEV_REL)/%,$(HEV_SRCFILES))
LOCAL_C_INCLUDES := \
    $(HEV_DIR)/include \
    $(HEV_DIR)/src \
    $(HEV_DIR)/src/misc \
    $(HEV_DIR)/src/core/include \
    $(HEV_DIR)/third-part/yaml/include \
    $(HEV_DIR)/third-part/lwip/src/include \
    $(HEV_DIR)/third-part/lwip/src/ports/include \
    $(HEV_DIR)/third-part/hev-task-system/include
LOCAL_CFLAGS += -DFD_SET_DEFINED -DSOCKLEN_T_DEFINED -DENABLE_LIBRARY
LOCAL_CFLAGS += $(VERSION_CFLAGS)
ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
LOCAL_CFLAGS += -mfpu=neon
endif
LOCAL_STATIC_LIBRARIES := yaml lwip hev-task-system
LOCAL_LDLIBS := -llog
# Upstream's page-size flags. Android 15+ requires 16 KB page alignment on
# 64-bit devices; a mismatch here only fails on some hardware.
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
include $(BUILD_SHARED_LIBRARY)
