LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := libtermux-bootstrap
LOCAL_SRC_FILES := termux-bootstrap-zip.S termux-bootstrap.c
include $(BUILD_SHARED_LIBRARY)

# Offline desktop packages (built by the bundleDesktopPackages Gradle task). Never loaded as code:
# the setup script reads the tar that follows the marker. Empty for architectures without a pack.
include $(CLEAR_VARS)
LOCAL_MODULE := liblxdesk-desktop-pack
LOCAL_SRC_FILES := lxdesk-desktop-pack.S lxdesk-desktop-pack.c
include $(BUILD_SHARED_LIBRARY)
