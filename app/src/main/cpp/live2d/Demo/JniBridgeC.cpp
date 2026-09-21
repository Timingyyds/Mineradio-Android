/**
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

#include "JniBridgeC.hpp"
#include <algorithm>
#include <jni.h>
#include <string.h>
#include "LAppDelegate.hpp"
#include "LAppPal.hpp"
#include "LAppLive2DManager.hpp"

using namespace Csm;

static JavaVM* g_JVM; // JavaVM is valid for all threads, so just save it globally
static jclass  g_JniBridgeJavaClass;
static jmethodID g_GetAssetsMethodId;
static jmethodID g_LoadFileMethodId;
static jmethodID g_MoveTaskToBackMethodId;

// ★★★ Live2D 引擎初始化守卫标志
// - nativeOnSurfaceCreated 时置 true，nativeOnDestroy 时置 false
// - 表情管理相关 JNI 调用必须先检查此标志，避免在引擎未初始化时
//   触发 LAppLive2DManager::GetInstance() 的构造链导致 SIGSEGV
static bool g_live2dInitialized = false;

JNIEnv* GetEnv()
{
    JNIEnv* env = NULL;
    g_JVM->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    return env;
}

// The VM calls JNI_OnLoad when the native library is loaded
jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved)
{
    g_JVM = vm;

    JNIEnv *env;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK)
    {
        return JNI_ERR;
    }

    jclass clazz = env->FindClass("com/mineradio/app/wallpaper/JniBridgePet");
    g_JniBridgeJavaClass = reinterpret_cast<jclass>(env->NewGlobalRef(clazz));
    g_GetAssetsMethodId = env->GetStaticMethodID(g_JniBridgeJavaClass, "GetAssetList", "(Ljava/lang/String;)[Ljava/lang/String;");
    g_LoadFileMethodId = env->GetStaticMethodID(g_JniBridgeJavaClass, "LoadFile", "(Ljava/lang/String;)[B");
    g_MoveTaskToBackMethodId = env->GetStaticMethodID(g_JniBridgeJavaClass, "MoveTaskToBack", "()V");

    return JNI_VERSION_1_6;
}

void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved)
{
    JNIEnv *env = GetEnv();
    env->DeleteGlobalRef(g_JniBridgeJavaClass);
}

Csm::csmVector<Csm::csmString>JniBridgeC::GetAssetList(const Csm::csmString& path)
{
    JNIEnv *env = GetEnv();
    jobjectArray obj = reinterpret_cast<jobjectArray>(env->CallStaticObjectMethod(g_JniBridgeJavaClass, g_GetAssetsMethodId, env->NewStringUTF(path.GetRawString())));
    unsigned int size = static_cast<unsigned int>(env->GetArrayLength(obj));
    Csm::csmVector<Csm::csmString> list(size);
    for (unsigned int i = 0; i < size; i++)
    {
        jstring jstr = reinterpret_cast<jstring>(env->GetObjectArrayElement(obj, i));
        const char* chars = env->GetStringUTFChars(jstr, nullptr);
        list.PushBack(Csm::csmString(chars));
        env->ReleaseStringUTFChars(jstr, chars);
        env->DeleteLocalRef(jstr);
    }
    return list;
}

char* JniBridgeC::LoadFileAsBytesFromJava(const char* filePath, unsigned int* outSize)
{
    JNIEnv *env = GetEnv();

    // ファイルロード
    jbyteArray obj = (jbyteArray)env->CallStaticObjectMethod(g_JniBridgeJavaClass, g_LoadFileMethodId, env->NewStringUTF(filePath));

    // ファイルが見つからなかったらnullが返ってくるためチェック
    if (!obj)
    {
        return NULL;
    }

    *outSize = static_cast<unsigned int>(env->GetArrayLength(obj));

    char* buffer = new char[*outSize];
    env->GetByteArrayRegion(obj, 0, *outSize, reinterpret_cast<jbyte *>(buffer));

    return buffer;
}

void JniBridgeC::MoveTaskToBack()
{
    JNIEnv *env = GetEnv();

    // アプリ終了
    env->CallStaticVoidMethod(g_JniBridgeJavaClass, g_MoveTaskToBackMethodId, NULL);
}

extern "C"
{
    JNIEXPORT void JNICALL
    Java_com_mineradio_app_wallpaper_JniBridgePet_nativeOnStart(JNIEnv *env, jclass type)
    {
        LAppDelegate::GetInstance()->OnStart();
    }

    JNIEXPORT void JNICALL
    Java_com_mineradio_app_wallpaper_JniBridgePet_nativeOnPause(JNIEnv *env, jclass type)
    {
        LAppDelegate::GetInstance()->OnPause();
    }

    JNIEXPORT void JNICALL
    Java_com_mineradio_app_wallpaper_JniBridgePet_nativeOnStop(JNIEnv *env, jclass type)
    {
        LAppDelegate::GetInstance()->OnStop();
    }

    JNIEXPORT void JNICALL
    Java_com_mineradio_app_wallpaper_JniBridgePet_nativeOnDestroy(JNIEnv *env, jclass type)
    {
        // ★ 引擎销毁时关闭守卫标志，防止后续 JNI 调用触发已释放资源的访问
        g_live2dInitialized = false;
        LAppDelegate::GetInstance()->OnDestroy();
    }

    JNIEXPORT void JNICALL
    Java_com_mineradio_app_wallpaper_JniBridgePet_nativeOnSurfaceCreated(JNIEnv *env, jclass type)
    {
        // ★ GL 上下文已创建，Live2D 引擎可以安全初始化
        g_live2dInitialized = true;
        LAppDelegate::GetInstance()->OnSurfaceCreate();
    }

    JNIEXPORT void JNICALL
    Java_com_mineradio_app_wallpaper_JniBridgePet_nativeOnSurfaceChanged(JNIEnv *env, jclass type, jint width, jint height)
    {
        LAppDelegate::GetInstance()->OnSurfaceChanged(width, height);
    }

    JNIEXPORT void JNICALL
    Java_com_mineradio_app_wallpaper_JniBridgePet_nativeOnDrawFrame(JNIEnv *env, jclass type)
    {
        LAppDelegate::GetInstance()->Run();
    }

    JNIEXPORT void JNICALL
    Java_com_mineradio_app_wallpaper_JniBridgePet_nativeOnTouchesBegan(JNIEnv *env, jclass type, jfloat pointX, jfloat pointY)
    {
        LAppDelegate::GetInstance()->OnTouchBegan(pointX, pointY);
    }

    JNIEXPORT void JNICALL
    Java_com_mineradio_app_wallpaper_JniBridgePet_nativeOnTouchesEnded(JNIEnv *env, jclass type, jfloat pointX, jfloat pointY)
    {
        LAppDelegate::GetInstance()->OnTouchEnded(pointX, pointY);
    }

    JNIEXPORT void JNICALL
    Java_com_mineradio_app_wallpaper_JniBridgePet_nativeOnTouchesMoved(JNIEnv *env, jclass type, jfloat pointX, jfloat pointY)
    {
        LAppDelegate::GetInstance()->OnTouchMoved(pointX, pointY);
    }

    // ★ 获取当前模型所有表情名称（用 \n 分隔）
    // - 守卫：Live2D 引擎未初始化时返回空字符串，避免触发构造链崩溃
    JNIEXPORT jstring JNICALL
    Java_com_mineradio_app_wallpaper_JniBridgePet_nativeGetExpressionNames(JNIEnv *env, jclass type)
    {
        if (!g_live2dInitialized)
        {
            // 引擎未初始化，返回空字符串
            return env->NewStringUTF("");
        }
        Csm::csmString names = LAppLive2DManager::GetInstance()->GetExpressionNames();
        return env->NewStringUTF(names.GetRawString());
    }

    // ★ 触发指定表情
    // - 守卫：Live2D 引擎未初始化时直接返回，避免崩溃
    JNIEXPORT void JNICALL
    Java_com_mineradio_app_wallpaper_JniBridgePet_nativeTriggerExpression(JNIEnv *env, jclass type, jstring name)
    {
        if (!g_live2dInitialized)
        {
            // 引擎未初始化，静默忽略
            return;
        }
        const char* chars = env->GetStringUTFChars(name, nullptr);
        if (chars)
        {
            LAppLive2DManager::GetInstance()->TriggerExpression(chars);
            env->ReleaseStringUTFChars(name, chars);
        }
    }

    // ★★★ 设置视角偏移（陀螺仪用）
    JNIEXPORT void JNICALL
    Java_com_mineradio_app_wallpaper_JniBridgePet_nativeSetViewOffset(JNIEnv *env, jclass type, jfloat x, jfloat y)
    {
        if (!g_live2dInitialized) return;
        LAppLive2DManager::GetInstance()->SetViewOffset(x, y);
    }
}
