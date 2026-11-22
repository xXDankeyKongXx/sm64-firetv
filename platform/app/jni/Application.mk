# Application Makefile #

APP_STL := c++_shared

# Fire TV: 32-bit ARM only (all Fire TVs run a 32-bit OS)
APP_ABI := armeabi-v7a
APP_PLATFORM := android-24

APP_CPPFLAGS += -fexceptions -frtti
