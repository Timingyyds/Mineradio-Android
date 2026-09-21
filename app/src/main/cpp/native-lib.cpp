#include <jni.h>
#include <string>
#include <cstdlib>
#include <pthread.h>
#include <unistd.h>
#include <android/log.h>

#include "node.h"
#include "rn-bridge.h"

// 缓存 JNIEnv 供 Node 线程回调 Java
JNIEnv* cacheEnvPointer = NULL;

extern "C"
JNIEXPORT void JNICALL
Java_com_mineradio_desktop_nodejs_NodeJSMobile_sendMessageToNodeChannel(
        JNIEnv *env, jclass, jstring channelName, jstring msg) {
    const char* nativeChannelName = env->GetStringUTFChars(channelName, 0);
    const char* nativeMessage = env->GetStringUTFChars(msg, 0);
    rn_bridge_notify(nativeChannelName, nativeMessage);
    env->ReleaseStringUTFChars(channelName, nativeChannelName);
    env->ReleaseStringUTFChars(msg, nativeMessage);
}

extern "C" int callintoNode(int argc, char *argv[]) {
    return node::Start(argc, argv);
}

#if defined(__arm__)
    #define CURRENT_ABI_NAME "armeabi-v7a"
#elif defined(__aarch64__)
    #define CURRENT_ABI_NAME "arm64-v8a"
#elif defined(__i386__)
    #define CURRENT_ABI_NAME "x86"
#elif defined(__x86_64__)
    #define CURRENT_ABI_NAME "x86_64"
#else
    #error "Unknown ABI"
#endif

extern "C"
JNIEXPORT jstring JNICALL
Java_com_mineradio_desktop_nodejs_NodeJSMobile_getCurrentABIName(JNIEnv *env, jclass) {
    return env->NewStringUTF(CURRENT_ABI_NAME);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_mineradio_desktop_nodejs_NodeJSMobile_registerNodeDataDirPath(JNIEnv *env, jclass, jstring dataDir) {
    const char* nativeDataDir = env->GetStringUTFChars(dataDir, 0);
    rn_register_node_data_dir_path(nativeDataDir);
    env->ReleaseStringUTFChars(dataDir, nativeDataDir);
}

void rcv_message(const char* channel_name, const char* msg) {
    JNIEnv *env = cacheEnvPointer;
    if (!env) return;
    jclass cls = env->FindClass("com/mineradio/desktop/nodejs/NodeJSMobile");
    if (cls != nullptr) {
        jmethodID m = env->GetStaticMethodID(cls, "sendMessageToApplication", "(Ljava/lang/String;Ljava/lang/String;)V");
        if (m != nullptr) {
            jstring jch = env->NewStringUTF(channel_name);
            jstring jmsg = env->NewStringUTF(msg);
            env->CallStaticVoidMethod(cls, m, jch, jmsg);
            env->DeleteLocalRef(jch);
            env->DeleteLocalRef(jmsg);
        }
        env->DeleteLocalRef(cls);
    }
}

int pipe_stdout[2];
int pipe_stderr[2];
pthread_t thread_stdout;
pthread_t thread_stderr;
const char *ADBTAG = "MineradioNode";

void *thread_stderr_func(void*) {
    ssize_t n; char buf[2048];
    while ((n = read(pipe_stderr[0], buf, sizeof buf - 1)) > 0) {
        if (buf[n-1] == '\n') --n;
        buf[n] = 0;
        __android_log_write(ANDROID_LOG_ERROR, ADBTAG, buf);
    }
    return 0;
}

void *thread_stdout_func(void*) {
    ssize_t n; char buf[2048];
    while ((n = read(pipe_stdout[0], buf, sizeof buf - 1)) > 0) {
        if (buf[n-1] == '\n') --n;
        buf[n] = 0;
        __android_log_write(ANDROID_LOG_INFO, ADBTAG, buf);
    }
    return 0;
}

int start_redirecting_stdout_stderr() {
    setvbuf(stdout, 0, _IONBF, 0);
    pipe(pipe_stdout);
    dup2(pipe_stdout[1], STDOUT_FILENO);
    setvbuf(stderr, 0, _IONBF, 0);
    pipe(pipe_stderr);
    dup2(pipe_stderr[1], STDERR_FILENO);
    if (pthread_create(&thread_stdout, 0, thread_stdout_func, 0) == -1) return -1;
    pthread_detach(thread_stdout);
    if (pthread_create(&thread_stderr, 0, thread_stderr_func, 0) == -1) return -1;
    pthread_detach(thread_stderr);
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_mineradio_desktop_nodejs_NodeJSMobile_startNodeWithArguments(
        JNIEnv *env, jclass, jobjectArray arguments, jstring modulesPath, jboolean redirect) {
    const char* path_path = env->GetStringUTFChars(modulesPath, 0);
    setenv("NODE_PATH", path_path, 1);
    env->ReleaseStringUTFChars(modulesPath, path_path);

    jsize argument_count = env->GetArrayLength(arguments);
    int c_arguments_size = 0;
    for (int i = 0; i < argument_count; i++) {
        c_arguments_size += strlen(env->GetStringUTFChars((jstring)env->GetObjectArrayElement(arguments, i), 0));
        c_arguments_size++;
    }
    char* args_buffer = (char*)calloc(c_arguments_size, sizeof(char));
    char* argv[argument_count];
    char* current_args_position = args_buffer;
    for (int i = 0; i < argument_count; i++) {
        const char* current_argument = env->GetStringUTFChars((jstring)env->GetObjectArrayElement(arguments, i), 0);
        strncpy(current_args_position, current_argument, strlen(current_argument));
        argv[i] = current_args_position;
        current_args_position += strlen(current_args_position) + 1;
    }

    rn_register_bridge_cb(&rcv_message);
    cacheEnvPointer = env;

    if (redirect) {
        if (start_redirecting_stdout_stderr() == -1) {
            __android_log_write(ANDROID_LOG_ERROR, ADBTAG, "Couldn't redirect stdout/stderr");
        }
    }
    return jint(callintoNode(argument_count, argv));
}
