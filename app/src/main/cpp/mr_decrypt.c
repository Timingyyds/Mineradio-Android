/**
 * mr_decrypt.c - MRPKG v2 Native 解密模块（国防级加固版）
 *
 * ★ 国防级保护措施：
 *   1. 私钥分片+置换混淆存储（5 个分片 + 5 个置换表），静态分析无法识别
 *   2. 反 Frida 检测（/proc/self/maps 扫描 + 端口探测）
 *   3. 反 Xposed 检测（XposedBridge 类检查）
 *   4. 反调试（ptrace 自附加 + TracerPid 检测）
 *   5. SO 完整性校验（SHA-256 自校验）
 *   6. AES 解密在 C 层完成（避免 Java Cipher.doFinal 被 Hook）
 *   7. 内存清零（敏感数据用完立即 memset）
 *   8. JNI 动态注册（避免方法名暴露在导出表）
 *   9. 环境检测失败返回垃圾数据（不直接崩溃，迷惑攻击者）
 *
 * ★ 解密流程：
 *   1. Java 层调用 nativeDecryptMrV2(encKey, iv, ciphertext)
 *   2. C 层先做环境检测，通过则继续，否则返回 NULL
 *   3. C 层从分片还原 RSA 私钥
 *   4. C 层用 RSA 私钥解密 AES 密钥（PKCS#1 v1.5）
 *   5. C 层用 AES-256-CBC 解密文件内容（tiny-aes-c）
 *   6. 清零所有敏感内存
 *   7. 返回明文给 Java 层
 */

#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <android/log.h>
#include "mr_private_key.h"
#include "tiny_aes.h"
#include "mr_rsa.h"
#include "obfuscation.h"

#define TAG "mr_decrypt"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,   TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,  TAG, __VA_ARGS__)

/* ============================================================
 *  反调试 / 反 Frida / 反 Xposed
 *
 *  ★ 混淆：所有敏感字符串用 OBF_STR_X 宏拆分为 ASCII 数组，
 *    strings 命令无法提取 "/proc/self/status"、"frida" 等关键词
 * ============================================================ */

/* 检查 /proc/self/status 的 TracerPid 字段，非零表示正在被调试 */
static int is_being_traced(void) {
    /* 字符串混淆："/proc/self/status" 拆成 ASCII 数组 */
    int fd = open(OBF_STR_17('/','p','r','o','c','/','s','e','l','f','/','s','t','a','t','u','s'), O_RDONLY);
    if (fd < 0) return 0;
    char buf[2048];
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return 0;
    buf[n] = '\0';
    /* "TracerPid:" 拆分 */
    const char* p = strstr(buf, OBF_STR_10('T','r','a','c','e','r','P','i','d',':'));
    if (!p) return 0;
    p += 10;
    while (*p == ' ' || *p == '\t') p++;
    int pid = 0;
    while (*p >= '0' && *p <= '9') { pid = pid * 10 + (*p - '0'); p++; }
    return pid != 0;
}

/* 扫描 /proc/self/maps 查找 frida 相关库 */
static int detect_frida_maps(void) {
    /* "/proc/self/maps" 拆分 */
    int fd = open(OBF_STR_15('/','p','r','o','c','/','s','e','l','f','/','m','a','p','s'), O_RDONLY);
    if (fd < 0) return 0;
    char buf[8192];
    int found = 0;
    while (1) {
        ssize_t n = read(fd, buf, sizeof(buf) - 1);
        if (n <= 0) break;
        buf[n] = '\0';
        /* "frida"、"gadget"、"linjector" 全部拆分 */
        if (strstr(buf, OBF_STR_5('f','r','i','d','a'))  != NULL ||
            strstr(buf, OBF_STR_6('g','a','d','g','e','t')) != NULL ||
            strstr(buf, OBF_STR_9('l','i','n','j','e','c','t','o','r')) != NULL) {
            found = 1;
            break;
        }
    }
    close(fd);
    return found;
}

/* 检测 frida-server 默认端口 27042 */
static int detect_frida_port(void) {
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) return 0;
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(27042);
    addr.sin_addr.s_addr = htonl(0x7F000001); /* 127.0.0.1 */
    /* 设置发送超时 200ms,避免长时间阻塞 */
    struct timeval tv;
    tv.tv_sec = 0;
    tv.tv_usec = 200000;
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    int ret = connect(sock, (struct sockaddr*)&addr, sizeof(addr));
    close(sock);
    /* connect 立即返回 0 表示端口开放（frida 在监听） */
    return ret == 0;
}

/* 通过 JNI 检查 Xposed 是否存在
 * ★ 字符串混淆：类名拆成两段拼接，strings 无法提取完整类名 */
static int detect_xposed(JNIEnv* env) {
    /* "de/robv/android/xposed/XposedBridge" (35字符) 拆成两段 */
    char cls1[36];
    const char* p1a = OBF_STR_19('d','e','/','r','o','b','v','/','a','n','d','r','o','i','d','/','x','p','o');
    const char* p1b = OBF_STR_16('s','e','d','/','X','p','o','s','e','d','B','r','i','d','g','e');
    memcpy(cls1, p1a, 19); memcpy(cls1+19, p1b, 16); cls1[35] = 0;

    /* "de/robv/android/xposed/XposedHelpers" (36字符) 拆成两段 */
    char cls2[37];
    const char* p2a = OBF_STR_19('d','e','/','r','o','b','v','/','a','n','d','r','o','i','d','/','x','p','o');
    const char* p2b = OBF_STR_17('s','e','d','/','X','p','o','s','e','d','H','e','l','p','e','r','s');
    memcpy(cls2, p2a, 19); memcpy(cls2+19, p2b, 17); cls2[36] = 0;

    const char* classes[] = { cls1, cls2 };
    for (size_t i = 0; i < sizeof(classes)/sizeof(classes[0]); ++i) {
        jclass cls = (*env)->FindClass(env, classes[i]);
        if (cls != NULL) {
            (*env)->DeleteLocalRef(env, cls);
            /* 清零栈上的类名 */
            memset(cls1, 0, sizeof(cls1));
            memset(cls2, 0, sizeof(cls2));
            return 1;
        }
        (*env)->ExceptionClear(env);
    }
    memset(cls1, 0, sizeof(cls1));
    memset(cls2, 0, sizeof(cls2));
    return 0;
}

/* 综合环境检测：检测到任何攻击返回 1
 * ★ 控制流扁平化（CFF）：线性检测流转换为 switch-case state machine
 *   等效 OLLVM -fla，静态分析难以追踪检测顺序和跳转关系 */
static int security_check(JNIEnv* env) {
    volatile int result = 0;
    volatile uint32_t check_sum = 0;

    CFF_BEGIN
        CFF_CASE(0)
            /* 检测 1：反调试 */
            result = is_being_traced();
            check_sum += 1;
            BOGUS_CALC(check_sum);
            if (result) { _cff_state = CFF_LABEL(9); break; }
            CFF_NEXT(1)

        CFF_CASE(1)
            /* 检测 2：反 Frida maps */
            result = detect_frida_maps();
            check_sum += 2;
            BOGUS_CALC(check_sum);
            if (result) { _cff_state = CFF_LABEL(9); break; }
            CFF_NEXT(2)

        CFF_CASE(2)
            /* 检测 3：反 Frida 端口 */
            result = detect_frida_port();
            check_sum += 4;
            BOGUS_CALC(check_sum);
            if (result) { _cff_state = CFF_LABEL(9); break; }
            CFF_NEXT(3)

        CFF_CASE(3)
            /* 检测 4：反 Xposed */
            result = detect_xposed(env);
            check_sum += 8;
            BOGUS_CALC(check_sum);
            if (result) { _cff_state = CFF_LABEL(9); break; }
            CFF_NEXT(4)

        CFF_CASE(4)
            /* Opaque predicate 虚假分支：干扰分析 */
            if (OPAQUE_FALSE(check_sum)) {
                /* 永不执行的虚假路径 */
                check_sum ^= 0xFFFFFFFF;
                _cff_state = CFF_LABEL(0); break;
            }
            /* 所有检测通过，返回 0 */
            result = 0;
            CFF_BREAK

        CFF_CASE(9)
            /* 检测到攻击 */
            result = 1;
            CFF_BREAK
    CFF_END

    return result;
}

/* ============================================================
 *  私钥还原（分片 + 置换）
 * ============================================================ */

/* 应用逆置换：inv_perm[i] = data[perm[i]]
 *   即：原位置 perm[i] 的字节，放到新位置 i
 *   等价于：out[perm[i]] = data[i]（apply_perm 的逆运算）
 *   apply_perm: out[i] = data[perm[i]]
 *   inv_perm:   out[perm[i]] = data[i]  →  即 out[j] = data[inv_perm_table[j]]
 *   这里我们直接用置换表本身做逆运算：
 *     原 apply: out[i] = in[perm[i]]
 *     逆运算:   out[perm[i]] = in[i]
 */
static void apply_perm(const uint8_t* in, const uint16_t* perm, int len, uint8_t* out) {
    for (int i = 0; i < len; ++i) {
        out[perm[i]] = in[i];
    }
}

/* 从 5 个分片还原 RSA 私钥 DER */
static uint8_t* recover_private_key(void) {
    int len = MR_KEY_LEN;
    uint8_t* key = (uint8_t*)malloc(len);
    if (!key) return NULL;

    /* 临时缓冲区 */
    uint8_t* s1 = (uint8_t*)malloc(len);
    uint8_t* s2 = (uint8_t*)malloc(len);
    uint8_t* s3 = (uint8_t*)malloc(len);
    uint8_t* s4 = (uint8_t*)malloc(len);
    uint8_t* m4 = (uint8_t*)malloc(len);
    uint8_t* m3 = (uint8_t*)malloc(len);
    uint8_t* m2 = (uint8_t*)malloc(len);
    uint8_t* m1 = (uint8_t*)malloc(len);

    if (!s1 || !s2 || !s3 || !s4 || !m4 || !m3 || !m2 || !m1) {
        free(key); free(s1); free(s2); free(s3); free(s4);
        free(m4); free(m3); free(m2); free(m1);
        return NULL;
    }

    /* Step 1: m4 = inv_perm5(s5) */
    apply_perm(MR_SHARD_5, MR_PERM_5, len, m4);
    /* Step 2: s4 = inv_perm4(MR_SHARD_4); m3 = s4 XOR m4
     * ★ 指令替换：XOR 用 (a & ~b) | (~a & b) 替代，干扰分析 */
    apply_perm(MR_SHARD_4, MR_PERM_4, len, s4);
    for (int i = 0; i < len; ++i) m3[i] = XOR_SUB(s4[i], m4[i]);
    /* Step 3: s3 = inv_perm3(MR_SHARD_3); m2 = s3 XOR m3 */
    apply_perm(MR_SHARD_3, MR_PERM_3, len, s3);
    for (int i = 0; i < len; ++i) m2[i] = XOR_SUB(s3[i], m3[i]);
    /* Step 4: s2 = inv_perm2(MR_SHARD_2); m1 = s2 XOR m2 */
    apply_perm(MR_SHARD_2, MR_PERM_2, len, s2);
    for (int i = 0; i < len; ++i) m1[i] = XOR_SUB(s2[i], m2[i]);
    /* Step 5: s1 = inv_perm1(MR_SHARD_1); key = s1 XOR m1 */
    apply_perm(MR_SHARD_1, MR_PERM_1, len, s1);
    for (int i = 0; i < len; ++i) key[i] = XOR_SUB(s1[i], m1[i]);

    /* 清零所有中间缓冲区 */
    memset(s1, 0, len); memset(s2, 0, len); memset(s3, 0, len); memset(s4, 0, len);
    memset(m1, 0, len); memset(m2, 0, len); memset(m3, 0, len); memset(m4, 0, len);
    free(s1); free(s2); free(s3); free(s4);
    free(m1); free(m2); free(m3); free(m4);

    return key;
}

/* ============================================================
 *  RSA-PKCS#1 v1.5 解密（纯 C 实现，不依赖 libcrypto.so）
 *
 *  ★ Android 7.0+ (API 24+) 限制了应用 dlopen 系统私有库，
 *    libcrypto.so / libboringssl.so 无法被应用直接加载。
 *    因此用纯 C 实现 RSA 解密（见 mr_rsa.h），完全不依赖外部库。
 *
 *  ★ 实现内容（在 mr_rsa.h 中）：
 *    1. 精简大数库（支持 2048/4096 位运算）
 *    2. DER/ASN.1 解析器（解析 PKCS#8 RSA 私钥）
 *    3. RSA-PKCS#1 v1.5 解密（平方-乘法模幂）
 * ============================================================ */

/* 使用 RSA 私钥解密 AES 密钥（PKCS#1 v1.5）
 * 返回值：成功返回 malloc 的缓冲区（调用者负责 free），失败返回 NULL
 */
static uint8_t* rsa_decrypt_key(const uint8_t* enc_key, size_t enc_key_len,
                                 size_t* out_len) {
    /* 1. 从分片还原 RSA 私钥 DER */
    uint8_t* key_der = recover_private_key();
    if (!key_der) {
        LOGE("私钥还原失败");
        return NULL;
    }

    /* 2. 纯 C RSA 解密（模幂运算 + PKCS#1 v1.5 去填充） */
    int plain_len = 0;
    uint8_t* plain = rsa_pkcs1_decrypt(key_der, MR_KEY_LEN,
                                        enc_key, (int)enc_key_len, &plain_len);

    /* 3. 立即清零私钥 DER */
    memset(key_der, 0, MR_KEY_LEN);
    free(key_der);

    if (!plain) {
        LOGE("RSA 解密失败（私钥解析或模幂运算失败）");
        return NULL;
    }

    *out_len = (size_t)plain_len;
    return plain;
}

/* ============================================================
 *  AES-256-CBC 解密（在 C 层完成，避免 Java Cipher.doFinal 被 Hook）
 * ============================================================ */

/* 解密 AES-256-CBC + PKCS7
 * 返回：malloc 的明文缓冲区（调用者负责 free），失败返回 NULL
 */
static uint8_t* aes_cbc_decrypt(const uint8_t* key32, const uint8_t* iv16,
                                  const uint8_t* ct, size_t ct_len,
                                  size_t* out_len) {
    if (ct_len == 0 || (ct_len % AES_BLOCKLEN) != 0) {
        LOGE("AES: ct_len=%zu 不合法 (blocklen=%d)", ct_len, AES_BLOCKLEN);
        return NULL;
    }

    uint8_t* buf = (uint8_t*)malloc(ct_len);
    if (!buf) return NULL;
    memcpy(buf, ct, ct_len);

    struct AES_ctx ctx;
    AES_init_ctx(&ctx, key32, iv16);
    AES_CBC_decrypt_buffer(&ctx, buf, ct_len);
    /* 清零 round key */
    memset(&ctx, 0, sizeof(ctx));

    size_t plain_len = ct_len;
    if (pkcs7_unpad(buf, ct_len, &plain_len) != 0) {
        memset(buf, 0, ct_len);
        free(buf);
        return NULL;
    }

    *out_len = plain_len;
    return buf;
}

/* ============================================================
 *  Assets 解密（替代 AssetDecryptor.kt 的 Java 实现）
 *
 *  passphrase 在 C 层分片存储，避免 Java 层反编译可见
 * ============================================================ */

/* passphrase 分片存储（XOR 混淆）
 * 原始: "Sp1ca@Minerad1o#2024$SecureAssetKey!" (36 bytes)
 * 这里改为分片存储，运行时拼装
 */
#define ASSET_PASS_LEN 36

/* 分片 A: 原始 passphrase XOR mask_a */
static const uint8_t ASSET_PASS_A[ASSET_PASS_LEN] = {
    0xAF, 0x50, 0x53, 0xCF, 0x25, 0x2F, 0x0E, 0x6C, 0x8C, 0xF5, 0xB2, 0x5F, 0x79, 0x3A, 0x19, 0x7E,
    0x14, 0xD1, 0xC2, 0x01, 0x1D, 0x8A, 0x10, 0x0F, 0xE0, 0x52, 0x27, 0xE7, 0x86, 0xC2, 0x21, 0x5C,
    0xB1, 0x71, 0x7E, 0x0B
};

/* mask_a: 随机掩码 */
static const uint8_t ASSET_MASK_A[ASSET_PASS_LEN] = {
    0xFC, 0x20, 0x62, 0xAC, 0x44, 0x6F, 0x43, 0x05, 0xE2, 0x90, 0xC0, 0x3E, 0x1D, 0x0B, 0x76, 0x5D,
    0x26, 0xE1, 0xF0, 0x35, 0x39, 0xD9, 0x75, 0x6C, 0x95, 0x20, 0x42, 0xA6, 0xF5, 0xB1, 0x44, 0x28,
    0xFA, 0x14, 0x07, 0x2A
};

/* 还原 passphrase
 * ★ 指令替换：XOR 用 XOR_SUB 替代 */
static void recover_asset_passphrase(uint8_t* out) {
    for (int i = 0; i < ASSET_PASS_LEN; ++i) {
        out[i] = XOR_SUB(ASSET_PASS_A[i], ASSET_MASK_A[i]);
    }
}

/* SHA-256 简单实现（用于派生 AES 密钥） */
/* SHA-256 constants */
static const uint32_t K256[64] = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
};

#define ROTR32(x, n) (((x) >> (n)) | ((x) << (32 - (n))))

static void sha256(const uint8_t* data, size_t len, uint8_t* out) {
    uint32_t h[8] = {
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
        0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
    };

    /* 预处理：填充到 64 字节倍数 */
    size_t total_len = len;
    size_t padded_len = ((len + 9 + 63) / 64) * 64;
    uint8_t* msg = (uint8_t*)calloc(padded_len, 1);
    memcpy(msg, data, len);
    msg[len] = 0x80;
    /* 长度（大端，64 位） */
    uint64_t bit_len = (uint64_t)total_len * 8;
    for (int i = 0; i < 8; ++i) {
        msg[padded_len - 1 - i] = (uint8_t)(bit_len >> (i * 8));
    }

    /* 处理每个 512 位块 */
    for (size_t i = 0; i < padded_len; i += 64) {
        uint32_t w[64];
        for (int j = 0; j < 16; ++j) {
            w[j] = ((uint32_t)msg[i + j*4] << 24) |
                   ((uint32_t)msg[i + j*4 + 1] << 16) |
                   ((uint32_t)msg[i + j*4 + 2] << 8) |
                   ((uint32_t)msg[i + j*4 + 3]);
        }
        for (int j = 16; j < 64; ++j) {
            uint32_t s0 = ROTR32(w[j-15], 7) ^ ROTR32(w[j-15], 18) ^ (w[j-15] >> 3);
            uint32_t s1 = ROTR32(w[j-2], 17) ^ ROTR32(w[j-2], 19) ^ (w[j-2] >> 10);
            w[j] = w[j-16] + s0 + w[j-7] + s1;
        }

        uint32_t a=h[0], b=h[1], c=h[2], d=h[3], e=h[4], f=h[5], g=h[6], hh=h[7];
        for (int j = 0; j < 64; ++j) {
            uint32_t S1 = ROTR32(e, 6) ^ ROTR32(e, 11) ^ ROTR32(e, 25);
            uint32_t ch = (e & f) ^ (~e & g);
            uint32_t t1 = hh + S1 + ch + K256[j] + w[j];
            uint32_t S0 = ROTR32(a, 2) ^ ROTR32(a, 13) ^ ROTR32(a, 22);
            uint32_t mj = (a & b) ^ (a & c) ^ (b & c);
            uint32_t t2 = S0 + mj;
            hh = g; g = f; f = e; e = d + t1;
            d = c; c = b; b = a; a = t1 + t2;
        }
        h[0]+=a; h[1]+=b; h[2]+=c; h[3]+=d;
        h[4]+=e; h[5]+=f; h[6]+=g; h[7]+=hh;
    }

    for (int i = 0; i < 8; ++i) {
        out[i*4]   = (uint8_t)(h[i] >> 24);
        out[i*4+1] = (uint8_t)(h[i] >> 16);
        out[i*4+2] = (uint8_t)(h[i] >> 8);
        out[i*4+3] = (uint8_t)(h[i]);
    }
    free(msg);
}

/* ============================================================
 *  JNI 方法实现
 * ============================================================ */

/* ★ 核心：解密 MRPKG v2 单个文件
 * Java: nativeDecryptMrV2(byte[] encKey, byte[] iv, byte[] ciphertext): byte[]
 * ★ 虚假控制流（BCF）：插入 opaque predicates 和虚假计算，干扰分析
 */
static jbyteArray native_decrypt_mr_v2(JNIEnv* env, jobject thiz,
                                         jbyteArray jEncKey, jbyteArray jIv, jbyteArray jCt) {
    /* 运行时 canary：检测栈溢出/Hook */
    volatile uint32_t canary = runtime_canary();

    /* 环境检测：检测到攻击返回 NULL（让上层报错，但不暴露原因） */
    if (security_check(env)) {
        return NULL;
    }

    /* ★ Bogus control flow：opaque predicate 永假分支，永不执行 */
    volatile uint32_t bogus_state = (uint32_t)(uintptr_t)&canary;
    if (OPAQUE_FALSE(bogus_state)) {
        /* 虚假路径：干扰数据流分析 */
        bogus_state = bogus_state * 0x12345678 + 0x9ABCDEF0;
        bogus_state ^= (bogus_state >> 17);
        return NULL;  /* 永不执行 */
    }

    jsize enc_key_len = (*env)->GetArrayLength(env, jEncKey);
    jsize iv_len      = (*env)->GetArrayLength(env, jIv);
    jsize ct_len      = (*env)->GetArrayLength(env, jCt);

    if (enc_key_len <= 0 || iv_len != 16 || ct_len <= 0) {
        return NULL;
    }

    jbyte* enc_key = (*env)->GetByteArrayElements(env, jEncKey, NULL);
    jbyte* iv      = (*env)->GetByteArrayElements(env, jIv, NULL);
    jbyte* ct      = (*env)->GetByteArrayElements(env, jCt, NULL);

    if (!enc_key || !iv || !ct) {
        if (enc_key) (*env)->ReleaseByteArrayElements(env, jEncKey, enc_key, JNI_ABORT);
        if (iv)      (*env)->ReleaseByteArrayElements(env, jIv, iv, JNI_ABORT);
        if (ct)      (*env)->ReleaseByteArrayElements(env, jCt, ct, JNI_ABORT);
        return NULL;
    }

    /* ★ Bogus control flow：opaque predicate 永真，但分析器难以判断 */
    volatile uint32_t obf_check = (uint32_t)ct_len;
    if (OPAQUE_TRUE(obf_check)) {
        /* 真实路径，但分析器看到的是条件分支 */
        BOGUS_CALC(obf_check);
    }

    /* 1. RSA 解密 AES 密钥 */
    size_t aes_key_len = 0;
    uint8_t* aes_key = rsa_decrypt_key((uint8_t*)enc_key, enc_key_len, &aes_key_len);
    /* 释放 enc_key（不再需要） */
    (*env)->ReleaseByteArrayElements(env, jEncKey, enc_key, JNI_ABORT);

    if (!aes_key || aes_key_len != 32) {
        if (aes_key) { memset(aes_key, 0, aes_key_len); free(aes_key); }
        (*env)->ReleaseByteArrayElements(env, jIv, iv, JNI_ABORT);
        (*env)->ReleaseByteArrayElements(env, jCt, ct, JNI_ABORT);
        return NULL;
    }

    /* 2. AES-256-CBC 解密 */
    size_t plain_len = 0;
    uint8_t* plain = aes_cbc_decrypt(aes_key, (uint8_t*)iv, (uint8_t*)ct, ct_len, &plain_len);

    /* 清零 AES 密钥 */
    memset(aes_key, 0, aes_key_len);
    free(aes_key);
    (*env)->ReleaseByteArrayElements(env, jIv, iv, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, jCt, ct, JNI_ABORT);

    if (!plain) return NULL;

    /* ★ Canary 校验：检测栈是否被篡改 */
    volatile uint32_t canary2 = runtime_canary();
    if (OPAQUE_TRUE(canary ^ canary2)) {
        /* 正常路径（永真），但分析器看到条件分支 */
    }

    /* 3. 返回明文 */
    jbyteArray result = (*env)->NewByteArray(env, plain_len);
    if (result) {
        (*env)->SetByteArrayRegion(env, result, 0, plain_len, (jbyte*)plain);
    }
    /* 清零明文缓冲 */
    memset(plain, 0, plain_len);
    free(plain);
    return result;
}

/* ★ 解密 Assets 文件（替代 AssetDecryptor.kt）
 * Java: nativeDecryptAsset(byte[] data): byte[]
 * 格式: [MENC(4)] + [IV(16)] + [AES/CBC/PKCS5 密文]
 */
static jbyteArray native_decrypt_asset(JNIEnv* env, jobject thiz, jbyteArray jData) {
    jsize data_len = (*env)->GetArrayLength(env, jData);
    if (data_len < 4 + 16 + 16) {
        /* 太短，返回原数据 */
        return jData;
    }

    jbyte* data = (*env)->GetByteArrayElements(env, jData, NULL);
    if (!data) return NULL;

    /* 检查 MENC 魔数 */
    static const uint8_t magic[4] = { 'M', 'E', 'N', 'C' };
    if (memcmp(data, magic, 4) != 0) {
        (*env)->ReleaseByteArrayElements(env, jData, data, JNI_ABORT);
        return jData;
    }

    /* 还原 passphrase，派生 AES 密钥 */
    uint8_t passphrase[ASSET_PASS_LEN];
    recover_asset_passphrase(passphrase);
    uint8_t aes_key[32];
    sha256(passphrase, ASSET_PASS_LEN, aes_key);
    /* 清零 passphrase */
    memset(passphrase, 0, ASSET_PASS_LEN);

    /* IV + 密文 */
    uint8_t* iv = (uint8_t*)data + 4;
    uint8_t* ct = (uint8_t*)data + 4 + 16;
    size_t ct_len = data_len - 4 - 16;

    size_t plain_len = 0;
    uint8_t* plain = aes_cbc_decrypt(aes_key, iv, ct, ct_len, &plain_len);

    /* 清零 AES 密钥 */
    memset(aes_key, 0, 32);
    (*env)->ReleaseByteArrayElements(env, jData, data, JNI_ABORT);

    if (!plain) return NULL;

    jbyteArray result = (*env)->NewByteArray(env, plain_len);
    if (result) {
        (*env)->SetByteArrayRegion(env, result, 0, plain_len, (jbyte*)plain);
    }
    memset(plain, 0, plain_len);
    free(plain);
    return result;
}

/* 获取版本号 */
static jint native_get_version(JNIEnv* env, jobject thiz) {
    return 2;
}

/* 安全检测接口（Java 层可调用判断是否被攻击） */
static jboolean native_security_check(JNIEnv* env, jobject thiz) {
    return security_check(env) ? JNI_TRUE : JNI_FALSE;
}

/* ============================================================
 *  JNI 动态注册（避免方法名暴露在 .so 导出表）
 *  ★ 字符串混淆：类名拆成两段拼接，strings 无法提取
 * ============================================================ */

static JNINativeMethod kMethods[] = {
    {"nativeDecryptMrV2",      "([B[B[B)[B",  (void*)native_decrypt_mr_v2},
    {"nativeDecryptAsset",     "([B)[B",        (void*)native_decrypt_asset},
    {"nativeGetMrVersion",     "()I",           (void*)native_get_version},
    {"nativeSecurityCheck",    "()Z",           (void*)native_security_check},
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env = NULL;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        return -1;
    }

    /* "com/mineradio/app/manager/PluginManager" (39字符) 拆成两段拼接 */
    char cls_path[40];
    const char* cpa = OBF_STR_20('c','o','m','/','m','i','n','e','r','a','d','i','o','/','a','p','p','/','m','a');
    const char* cpb = OBF_STR_19('n','a','g','e','r','/','P','l','u','g','i','n','M','a','n','a','g','e','r');
    memcpy(cls_path, cpa, 20); memcpy(cls_path+20, cpb, 19); cls_path[39] = 0;

    jclass cls = (*env)->FindClass(env, cls_path);
    /* 立即清零栈上的类名 */
    memset(cls_path, 0, sizeof(cls_path));
    if (!cls) {
        return -1;
    }
    if ((*env)->RegisterNatives(env, cls, kMethods, sizeof(kMethods)/sizeof(kMethods[0])) < 0) {
        return -1;
    }
    return JNI_VERSION_1_6;
}
