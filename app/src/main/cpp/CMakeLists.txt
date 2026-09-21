# CMakeLists.txt for MR Native Decrypt Module
# 构建 libmr_decrypt.so（国防级加固版）
#
# ★ 编译选项强化：
#   - -O2                : 优化级别
#   - -fvisibility=hidden : 隐藏内部符号
#   - -ffunction-sections / -fdata-sections : 分段
#   - -Wl,--gc-sections  : 移除未使用段
#   - -Wl,--strip-all    : 去除所有符号信息
#   - -fstack-protector-all : 栈溢出保护

cmake_minimum_required(VERSION 3.22.1)
project(mr_decrypt)

# C 标准
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# 创建共享库
add_library(mr_decrypt
    SHARED
    mr_decrypt.c
)

# 包含目录
target_include_directories(mr_decrypt
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# 链接 Android 系统库
find_library(log-lib
    log
)

target_link_libraries(mr_decrypt
    ${log-lib}
    # ★ 纯 C 实现 RSA 解密（mr_rsa.h），不依赖 libcrypto.so
    #   Android 7.0+ 限制了应用 dlopen 系统私有库，
    #   因此用纯 C 实现大数运算和 RSA 模幂，完全不依赖外部加密库
)

# ★ 编译选项：优化 + 去除符号 + 安全加固 + 混淆强化
target_compile_options(mr_decrypt
    PRIVATE
    -O2                 # 优化级别（不用 O3 避免过度内联暴露逻辑）
    -fvisibility=hidden # 隐藏内部符号
    -ffunction-sections # 函数分段
    -fdata-sections     # 数据分段
    -fstack-protector-all # 栈溢出保护
    -fno-rtti           # 禁用 RTTI（C 不需要，减小体积）
    -fno-exceptions     # 禁用异常处理（C 不需要）
    -fno-unwind-tables  # 禁用 unwind 表（减小体积，增加逆向难度）
    -fno-asynchronous-unwind-tables  # 禁用异步 unwind 表
    -Wall
    -Wno-unused-parameter
)

# ★ 链接选项：去除未使用段 + 去除所有符号信息 + 安全强化
target_link_options(mr_decrypt
    PRIVATE
    -Wl,--gc-sections   # 移除未使用段
    -Wl,--strip-all     # 去除所有符号信息（包括调试、符号表）
    -Wl,--exclude-libs,ALL  # 不导出依赖库的符号
    -Wl,--build-id=none # 不生成 build id（减少可识别信息）
    -Wl,-z,noexecstack  # 栈不可执行
    -Wl,-z,relro,-z,now # GOT 只读（防止 GOT 覆写攻击）
    -Wl,-z,noexecstack  # 标记栈为不可执行
)

# ============================================================
# live2d_pet 模块（Live2D Cubism SDK for Native）
# 构建 liblive2d_pet.so，仅 arm64-v8a
# ============================================================

# C++14 标准
set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# 路径定义
set(LIVE2D_PATH ${CMAKE_CURRENT_SOURCE_DIR}/live2d)
set(CORE_PATH ${LIVE2D_PATH}/Core)
set(FRAMEWORK_PATH ${LIVE2D_PATH}/Framework)
set(COMMON_SRC_PATH ${LIVE2D_PATH}/Common)
set(STB_PATH ${LIVE2D_PATH}/stb)

# 导入 Live2DCubismCore 静态库
add_library(Live2DCubismCore STATIC IMPORTED)
set_target_properties(Live2DCubismCore
  PROPERTIES
    IMPORTED_LOCATION
      ${CORE_PATH}/lib/android/${ANDROID_ABI}/libLive2DCubismCore.a
    INTERFACE_INCLUDE_DIRECTORIES ${CORE_PATH}/include
)

# 添加 Framework 子目录（指定 OpenGL 渲染路径）
set(FRAMEWORK_SOURCE OpenGL)
add_subdirectory(${FRAMEWORK_PATH} ${CMAKE_CURRENT_BINARY_DIR}/Framework)
# 设置 Framework 编译定义: Android ES2
target_compile_definitions(Framework PUBLIC CSM_TARGET_ANDROID_ES2)

# 创建 live2d_pet 共享库
add_library(live2d_pet SHARED)

# Common 源码
target_sources(live2d_pet
  PRIVATE
    ${COMMON_SRC_PATH}/LAppAllocator_Common.cpp
    ${COMMON_SRC_PATH}/LAppModel_Common.cpp
    ${COMMON_SRC_PATH}/LAppSprite_Common.cpp
    ${COMMON_SRC_PATH}/LAppTextureManager_Common.cpp
    ${COMMON_SRC_PATH}/LAppView_Common.cpp
    ${COMMON_SRC_PATH}/LAppWavFileHandler_Common.cpp
    ${COMMON_SRC_PATH}/TouchManager_Common.cpp
)

# Demo 源码
target_sources(live2d_pet
  PRIVATE
    ${LIVE2D_PATH}/Demo/JniBridgeC.cpp
    ${LIVE2D_PATH}/Demo/LAppDefine.cpp
    ${LIVE2D_PATH}/Demo/LAppDelegate.cpp
    ${LIVE2D_PATH}/Demo/LAppLive2DManager.cpp
    ${LIVE2D_PATH}/Demo/LAppModel.cpp
    ${LIVE2D_PATH}/Demo/LAppPal.cpp
    ${LIVE2D_PATH}/Demo/LAppSprite.cpp
    ${LIVE2D_PATH}/Demo/LAppSpriteShader.cpp
    ${LIVE2D_PATH}/Demo/LAppTextureManager.cpp
    ${LIVE2D_PATH}/Demo/LAppView.cpp
)

# 包含目录
target_include_directories(live2d_pet
  PRIVATE
    ${STB_PATH}
    ${COMMON_SRC_PATH}
    ${LIVE2D_PATH}/Demo
)

# 链接库
target_link_libraries(live2d_pet
  Framework
  Live2DCubismCore
  GLESv2
  log
)

# 链接选项: 16KB 页对齐（Android 15+ 兼容）
target_link_options(live2d_pet PRIVATE "-Wl,-z,max-page-size=16384")

# ============================================================
# Mineradio Node.js Mobile 原生桥接库（login-panel 后端）
# 输出 libmineradio-node-native.so，Java 层 System.loadLibrary("mineradio-node-native")
# ============================================================

# libnode.so 预编译库（nodejs-mobile-react-native 18.20.4）
# 放在 app/src/main/cpp/libnode/<ABI>/libnode.so
add_library(node SHARED IMPORTED)
set_target_properties(node PROPERTIES
    IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/libnode/${ANDROID_ABI}/libnode.so)

# Node.js 头文件
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/include/node)

add_library(mineradio-node-native SHARED
    native-lib.cpp
    rn-bridge.cpp)

find_library(log-lib2 log)

target_link_libraries(mineradio-node-native
    node
    ${log-lib2})
