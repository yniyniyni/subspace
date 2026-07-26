# SPDX-License-Identifier: AGPL-3.0-or-later
#
# No rules needed yet: M0 ships no product code beyond the application entry
# point, Hilt root, and a Material 3 theme, none of which R8 has trouble
# with. This file exists so the `release` build type's isMinifyEnabled/
# proguardFiles wiring in app/build.gradle.kts does not fail R8 with
# "Supplied proguard configuration does not exist".
#
# M1 will need real keep rules here: libXray ships Go-generated JNI classes
# (accessed via gomobile bindings) that R8 will strip or rename unless they
# are kept explicitly. Revisit this file when :core:xray's libXray AAR
# lands.
