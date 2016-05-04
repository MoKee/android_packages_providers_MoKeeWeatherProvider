#
# Copyright (C) 2016 The MoKee Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_PACKAGE_NAME := MoKeeWeatherProvider
LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_MODULE_TAGS := optional

LOCAL_STATIC_JAVA_LIBRARIES := \
    org.mokee.platform.sdk \
    sqlcipher

LOCAL_PROGUARD_FLAG_FILES := proguard.flags

ifeq ($(MK_CPU_ABI),arm64-v8a)
LOCAL_MULTILIB := 32
LOCAL_PREBUILT_JNI_LIBS := \
    jni/armeabi-v7a/libdatabase_sqlcipher.so \
    jni/armeabi-v7a/libsecurity.so \
    jni/armeabi-v7a/libsqlcipher_android.so \
    jni/armeabi-v7a/libstlport_shared.so
else
LOCAL_PREBUILT_JNI_LIBS := \
    jni/$(MK_CPU_ABI)/libdatabase_sqlcipher.so \
    jni/$(MK_CPU_ABI)/libsecurity.so \
    jni/$(MK_CPU_ABI)/libsqlcipher_android.so \
    jni/$(MK_CPU_ABI)/libstlport_shared.so
endif

include $(BUILD_PACKAGE)

include $(CLEAR_VARS)

LOCAL_PREBUILT_STATIC_JAVA_LIBRARIES := \
    sqlcipher:libs/sqlcipher.jar

include $(BUILD_MULTI_PREBUILT)
include $(call all-makefiles-under,$(LOCAL_PATH))