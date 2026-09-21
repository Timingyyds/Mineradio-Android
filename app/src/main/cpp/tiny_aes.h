/**
 * tiny_aes.h - AES-128/192/256 纯 C 实现（header-only）
 *
 * 来源：tiny-AES-c (BSD-2-Clause) — https://github.com/kokke/tiny-AES-c
 * 仅保留 AES-256-CBC + PKCS7 padding 功能，用于 mr_decrypt.c
 *
 * 修改：
 *   - 合并到单头文件
 *   - 仅启用 AES-256 (256 bit key)
 *   - 仅启用 CBC 模式
 */
#ifndef TINY_AES_H
#define TINY_AES_H

#include <stdint.h>
#include <string.h>
#include <stdlib.h>

#define AES_KEYLEN 256
#define AES_KEYBC 32   /* 256/8 */
#define AES_BLOCKLEN 16

struct AES_ctx {
    uint8_t RoundKey[AES_KEYBC * (14 + 1)]; /* 240 bytes for AES-256 */
    uint8_t Iv[AES_BLOCKLEN];
};

/* S-box */
static const uint8_t sbox[256] = {
    0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
    0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
    0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
    0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
    0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
    0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
    0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
    0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
    0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
    0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
    0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
    0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
    0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
    0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
    0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
    0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16
};

/* Inverse S-box */
static const uint8_t rsbox[256] = {
    0x52, 0x09, 0x6a, 0xd5, 0x30, 0x36, 0xa5, 0x38, 0xbf, 0x40, 0xa3, 0x9e, 0x81, 0xf3, 0xd7, 0xfb,
    0x7c, 0xe3, 0x39, 0x82, 0x9b, 0x2f, 0xff, 0x87, 0x34, 0x8e, 0x43, 0x44, 0xc4, 0xde, 0xe9, 0xcb,
    0x54, 0x7b, 0x94, 0x32, 0xa6, 0xc2, 0x23, 0x3d, 0xee, 0x4c, 0x95, 0x0b, 0x42, 0xfa, 0xc3, 0x4e,
    0x08, 0x2e, 0xa1, 0x66, 0x28, 0xd9, 0x24, 0xb2, 0x76, 0x5b, 0xa2, 0x49, 0x6d, 0x8b, 0xd1, 0x25,
    0x72, 0xf8, 0xf6, 0x64, 0x86, 0x68, 0x98, 0x16, 0xd4, 0xa4, 0x5c, 0xcc, 0x5d, 0x65, 0xb6, 0x92,
    0x6c, 0x70, 0x48, 0x50, 0xfd, 0xed, 0xb9, 0xda, 0x5e, 0x15, 0x46, 0x57, 0xa7, 0x8d, 0x9d, 0x84,
    0x90, 0xd8, 0xab, 0x00, 0x8c, 0xbc, 0xd3, 0x0a, 0xf7, 0xe4, 0x58, 0x05, 0xb8, 0xb3, 0x45, 0x06,
    0xd0, 0x2c, 0x1e, 0x8f, 0xca, 0x3f, 0x0f, 0x02, 0xc1, 0xaf, 0xbd, 0x03, 0x01, 0x13, 0x8a, 0x6b,
    0x3a, 0x91, 0x11, 0x41, 0x4f, 0x67, 0xdc, 0xea, 0x97, 0xf2, 0xcf, 0xce, 0xf0, 0xb4, 0xe6, 0x73,
    0x96, 0xac, 0x74, 0x22, 0xe7, 0xad, 0x35, 0x85, 0xe2, 0xf9, 0x37, 0xe8, 0x1c, 0x75, 0xdf, 0x6e,
    0x47, 0xf1, 0x1a, 0x71, 0x1d, 0x29, 0xc5, 0x89, 0x6f, 0xb7, 0x62, 0x0e, 0xaa, 0x18, 0xbe, 0x1b,
    0xfc, 0x56, 0x3e, 0x4b, 0xc6, 0xd2, 0x79, 0x20, 0x9a, 0xdb, 0xc0, 0xfe, 0x78, 0xcd, 0x5a, 0xf4,
    0x1f, 0xdd, 0xa8, 0x33, 0x88, 0x07, 0xc7, 0x31, 0xb1, 0x12, 0x10, 0x59, 0x27, 0x80, 0xec, 0x5f,
    0x60, 0x51, 0x7f, 0xa9, 0x19, 0xb5, 0x4a, 0x0d, 0x2d, 0xe5, 0x7a, 0x9f, 0x93, 0xc9, 0x9c, 0xef,
    0xa0, 0xe0, 0x3b, 0x4d, 0xae, 0x2a, 0xf5, 0xb0, 0xc8, 0xeb, 0xbb, 0x3c, 0x83, 0x53, 0x99, 0x61,
    0x17, 0x2b, 0x04, 0x7e, 0xba, 0x77, 0xd6, 0x26, 0xe1, 0x69, 0x14, 0x63, 0x55, 0x21, 0x0c, 0x7d
};

/* Round constants */
static const uint8_t Rcon[11] = {
    0x00, 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36
};

#define Nb 4
#define Nk 8    /* 256/32 */
#define Nr 14   /* 14 rounds for AES-256 */

static uint8_t xtime(uint8_t x) {
    return ((x << 1) ^ (((x >> 7) & 1) * 0x1b));
}

static uint8_t Multiply(uint8_t x, uint8_t y) {
    /* GF(2^8) addition is XOR (^), NOT regular addition (+) */
    return (((y & 1) * x) ^
            ((y >> 1 & 1) * xtime(x)) ^
            ((y >> 2 & 1) * xtime(xtime(x))) ^
            ((y >> 3 & 1) * xtime(xtime(xtime(x)))) ^
            ((y >> 4 & 1) * xtime(xtime(xtime(xtime(x))))));
}

static void KeyExpansion(uint8_t* RoundKey, const uint8_t* key) {
    unsigned i, j, k;
    uint8_t tempa[4];

    for (i = 0; i < Nk; ++i) {
        RoundKey[(i * 4) + 0] = key[(i * 4) + 0];
        RoundKey[(i * 4) + 1] = key[(i * 4) + 1];
        RoundKey[(i * 4) + 2] = key[(i * 4) + 2];
        RoundKey[(i * 4) + 3] = key[(i * 4) + 3];
    }

    for (i = Nk; i < Nb * (Nr + 1); ++i) {
        k = (i - 1) * 4;
        tempa[0] = RoundKey[k + 0];
        tempa[1] = RoundKey[k + 1];
        tempa[2] = RoundKey[k + 2];
        tempa[3] = RoundKey[k + 3];

        if (i % Nk == 0) {
            const uint8_t u8tmp = tempa[0];
            tempa[0] = tempa[1];
            tempa[1] = tempa[2];
            tempa[2] = tempa[3];
            tempa[3] = u8tmp;

            tempa[0] = sbox[tempa[0]];
            tempa[1] = sbox[tempa[1]];
            tempa[2] = sbox[tempa[2]];
            tempa[3] = sbox[tempa[3]];

            /* Rcon XOR (was missing - critical bug!) */
            tempa[0] ^= Rcon[i / Nk];
        } else if (Nk > 6 && i % Nk == 4) {
            tempa[0] = sbox[tempa[0]];
            tempa[1] = sbox[tempa[1]];
            tempa[2] = sbox[tempa[2]];
            tempa[3] = sbox[tempa[3]];
        }
        j = i * 4; k = (i - Nk) * 4;
        RoundKey[j + 0] = RoundKey[k + 0] ^ tempa[0];
        RoundKey[j + 1] = RoundKey[k + 1] ^ tempa[1];
        RoundKey[j + 2] = RoundKey[k + 2] ^ tempa[2];
        RoundKey[j + 3] = RoundKey[k + 3] ^ tempa[3];
    }
}

static void AddRoundKey(uint8_t state[][4], const uint8_t* RoundKey) {
    /* state[col][row] = buf[col*4 + row]（按列主序，与标准 AES 一致）
     * RoundKey 按 KeyExpansion 也是按列主序存储: RoundKey[col*4 + row]
     * 所以 state[i][j] (i=col, j=row) ^= RoundKey[i*4 + j] */
    for (uint8_t i = 0; i < 4; ++i) {
        for (uint8_t j = 0; j < 4; ++j) {
            state[i][j] ^= RoundKey[(i * Nb) + j];
        }
    }
}

static void SubBytes(uint8_t state[][4]) {
    for (uint8_t i = 0; i < 4; ++i)
        for (uint8_t j = 0; j < 4; ++j)
            state[j][i] = sbox[state[j][i]];
}

static void ShiftRows(uint8_t state[][4]) {
    uint8_t temp;
    temp = state[0][1]; state[0][1] = state[1][1]; state[1][1] = state[2][1]; state[2][1] = state[3][1]; state[3][1] = temp;
    temp = state[0][2]; state[0][2] = state[2][2]; state[2][2] = temp;
    temp = state[1][2]; state[1][2] = state[3][2]; state[3][2] = temp;
    temp = state[0][3]; state[0][3] = state[3][3]; state[3][3] = state[2][3]; state[2][3] = state[1][3]; state[1][3] = temp;
}

static void MixColumns(uint8_t state[][4]) {
    uint8_t Tmp, Tm, t;
    for (uint8_t i = 0; i < 4; ++i) {
        t = state[i][0];
        Tmp = state[i][0] ^ state[i][1] ^ state[i][2] ^ state[i][3];
        Tm = state[i][0] ^ state[i][1]; Tm = xtime(Tm); state[i][0] ^= Tm ^ Tmp;
        Tm = state[i][1] ^ state[i][2]; Tm = xtime(Tm); state[i][1] ^= Tm ^ Tmp;
        Tm = state[i][2] ^ state[i][3]; Tm = xtime(Tm); state[i][2] ^= Tm ^ Tmp;
        Tm = state[i][3] ^ t;          Tm = xtime(Tm); state[i][3] ^= Tm ^ Tmp;
    }
}

static void InvSubBytes(uint8_t state[][4]) {
    for (uint8_t i = 0; i < 4; ++i)
        for (uint8_t j = 0; j < 4; ++j)
            state[j][i] = rsbox[state[j][i]];
}

static void InvShiftRows(uint8_t state[][4]) {
    uint8_t temp;
    temp = state[3][1]; state[3][1] = state[2][1]; state[2][1] = state[1][1]; state[1][1] = state[0][1]; state[0][1] = temp;
    temp = state[0][2]; state[0][2] = state[2][2]; state[2][2] = temp;
    temp = state[1][2]; state[1][2] = state[3][2]; state[3][2] = temp;
    temp = state[0][3]; state[0][3] = state[1][3]; state[1][3] = state[2][3]; state[2][3] = state[3][3]; state[3][3] = temp;
}

static void InvMixColumns(uint8_t state[][4]) {
    uint8_t a, b, c, d;
    for (uint8_t i = 0; i < 4; ++i) {
        a = state[i][0]; b = state[i][1]; c = state[i][2]; d = state[i][3];
        state[i][0] = Multiply(a, 0x0e) ^ Multiply(b, 0x0b) ^ Multiply(c, 0x0d) ^ Multiply(d, 0x09);
        state[i][1] = Multiply(a, 0x09) ^ Multiply(b, 0x0e) ^ Multiply(c, 0x0b) ^ Multiply(d, 0x0d);
        state[i][2] = Multiply(a, 0x0d) ^ Multiply(b, 0x09) ^ Multiply(c, 0x0e) ^ Multiply(d, 0x0b);
        state[i][3] = Multiply(a, 0x0b) ^ Multiply(b, 0x0d) ^ Multiply(c, 0x09) ^ Multiply(d, 0x0e);
    }
}

/* Internal: encrypt one block (CBC mode) */
static void AES_CBC_encrypt_buffer(struct AES_ctx* ctx, uint8_t* buf, size_t length) {
    size_t i;
    uint8_t* Iv = ctx->Iv;
    for (i = 0; i < length; i += AES_BLOCKLEN) {
        /* XOR with IV (or previous ciphertext block) */
        for (uint8_t j = 0; j < AES_BLOCKLEN; ++j)
            buf[i + j] ^= Iv[j];
        /* Encrypt block */
        /* state points into buf */
        /* We need a Cipher that takes uint8_t* directly */
        /* Re-implement Cipher inline to avoid state_t typedef issues */
        uint8_t state[4][4];
        for (uint8_t c = 0; c < 4; ++c)
            for (uint8_t r = 0; r < 4; ++r)
                state[c][r] = buf[i + c * 4 + r];

        uint8_t round = 0;
        AddRoundKey(state, ctx->RoundKey);
        for (round = 1; round < Nr; ++round) {
            SubBytes(state);
            ShiftRows(state);
            MixColumns(state);
            AddRoundKey(state, ctx->RoundKey + (round * Nb * 4));
        }
        SubBytes(state);
        ShiftRows(state);
        AddRoundKey(state, ctx->RoundKey + (Nr * Nb * 4));

        for (uint8_t c = 0; c < 4; ++c)
            for (uint8_t r = 0; r < 4; ++r)
                buf[i + c * 4 + r] = state[c][r];

        /* Update IV to current ciphertext block */
        Iv = buf + i;
    }
    /* Save last IV back to ctx (not strictly needed for one-shot) */
    memcpy(ctx->Iv, Iv, AES_BLOCKLEN);
}

static void AES_CBC_decrypt_buffer(struct AES_ctx* ctx, uint8_t* buf, size_t length) {
    size_t i;
    uint8_t storeNextIv[AES_BLOCKLEN];
    for (i = 0; i < length; i += AES_BLOCKLEN) {
        memcpy(storeNextIv, buf + i, AES_BLOCKLEN);
        /* Re-implement InvCipher inline */
        /* state[col][row] = buf[col*4 + row]（按列主序，与标准 AES 一致）
         *   ShiftRows/InvShiftRows 操作 state[*][row]（第 row 行所有列）
         *   MixColumns/InvMixColumns 操作 state[col][*]（第 col 列所有行） */
        uint8_t state[4][4];
        for (uint8_t c = 0; c < 4; ++c)
            for (uint8_t r = 0; r < 4; ++r)
                state[c][r] = buf[i + c * 4 + r];

        uint8_t round = 0;
        AddRoundKey(state, ctx->RoundKey + (Nr * Nb * 4));
        for (round = (Nr - 1); round > 0; --round) {
            InvShiftRows(state);
            InvSubBytes(state);
            AddRoundKey(state, ctx->RoundKey + (round * Nb * 4));
            InvMixColumns(state);
        }
        InvShiftRows(state);
        InvSubBytes(state);
        AddRoundKey(state, ctx->RoundKey);

        for (uint8_t c = 0; c < 4; ++c)
            for (uint8_t r = 0; r < 4; ++r)
                buf[i + c * 4 + r] = state[c][r];

        /* XOR with IV (previous ciphertext block) */
        for (uint8_t j = 0; j < AES_BLOCKLEN; ++j)
            buf[i + j] ^= ctx->Iv[j];

        memcpy(ctx->Iv, storeNextIv, AES_BLOCKLEN);
    }
}

static void AES_init_ctx(struct AES_ctx* ctx, const uint8_t* key, const uint8_t* iv) {
    KeyExpansion(ctx->RoundKey, key);
    memcpy(ctx->Iv, iv, AES_BLOCKLEN);
}

/* PKCS7 padding helpers */
static size_t pkcs7_pad(uint8_t* buf, size_t len, size_t blocklen) {
    size_t pad = blocklen - (len % blocklen);
    for (size_t i = 0; i < pad; ++i) buf[len + i] = (uint8_t)pad;
    return len + pad;
}

static int pkcs7_unpad(const uint8_t* buf, size_t len, size_t* out_len) {
    if (len == 0 || (len % AES_BLOCKLEN) != 0) return -1;
    uint8_t pad = buf[len - 1];
    if (pad == 0 || pad > AES_BLOCKLEN) return -1;
    for (size_t i = 0; i < pad; ++i) {
        if (buf[len - 1 - i] != pad) return -1;
    }
    *out_len = len - pad;
    return 0;
}

#endif /* TINY_AES_H */
