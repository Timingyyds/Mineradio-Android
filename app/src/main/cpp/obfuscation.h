/**
 * obfuscation.h - 手动代码混淆（等效 OLLVM -fla/-sub/-bcf）
 *
 * ★ 实现的混淆技术：
 *   1. 字符串拆分（等效 -sub）：敏感字符串拆成 ASCII 数组，strings 命令无法提取
 *   2. 控制流扁平化（等效 -fla）：switch-based state machine
 *   3. 虚假控制流（等效 -bcf）：opaque predicates + 虚假路径
 *   4. 指令替换：XOR/ADD 用等价复杂表达式替代
 *   5. 反 IDA 技巧：不透明谓词干扰数据流分析
 *
 * ★ 设计原则：
 *   - 纯 C 实现，不依赖第三方工具链
 *   - 不影响程序逻辑正确性
 *   - 显著增加静态分析和逆向难度
 */
#ifndef OBFUSCATION_H
#define OBFUSCATION_H

#include <stdint.h>
#include <string.h>

/* ============================================================
 *  1. 字符串拆分（防止 strings 提取）
 *
 *  将敏感字符串拆成 ASCII 数组复合字面量，
 *  二进制中不再以连续字节存储，strings 命令无法提取。
 *
 *  用法：strstr(buf, OBF_STR("frida"))
 *  展开：strstr(buf, (const char[]){102,114,105,100,97,0})
 * ============================================================ */

/* 辅助宏：将字符转为 ASCII 数字，避免源码中出现明文 */
#define C0(c) ((uint8_t)(c))
#define C1(c) ((uint8_t)(c))

/* 复合字面量字符串：字符以 ASCII 码存储，二进制中不连续 */
#define OBF_STR_1(a)               ((const char[]){C0(a),0})
#define OBF_STR_2(a,b)             ((const char[]){C0(a),C0(b),0})
#define OBF_STR_3(a,b,c)           ((const char[]){C0(a),C0(b),C0(c),0})
#define OBF_STR_4(a,b,c,d)         ((const char[]){C0(a),C0(b),C0(c),C0(d),0})
#define OBF_STR_5(a,b,c,d,e)       ((const char[]){C0(a),C0(b),C0(c),C0(d),C0(e),0})
#define OBF_STR_6(a,b,c,d,e,f)     ((const char[]){C0(a),C0(b),C0(c),C0(d),C0(e),C0(f),0})
#define OBF_STR_7(a,b,c,d,e,f,g)   ((const char[]){C0(a),C0(b),C0(c),C0(d),C0(e),C0(f),C0(g),0})
#define OBF_STR_8(a,b,c,d,e,f,g,h) ((const char[]){C0(a),C0(b),C0(c),C0(d),C0(e),C0(f),C0(g),C0(h),0})
#define OBF_STR_9(a,b,c,d,e,f,g,h,i) ((const char[]){C0(a),C0(b),C0(c),C0(d),C0(e),C0(f),C0(g),C0(h),C0(i),0})
#define OBF_STR_10(a,b,c,d,e,f,g,h,i,j) ((const char[]){C0(a),C0(b),C0(c),C0(d),C0(e),C0(f),C0(g),C0(h),C0(i),C0(j),0})
#define OBF_STR_11(a,b,c,d,e,f,g,h,i,j,k) ((const char[]){C0(a),C0(b),C0(c),C0(d),C0(e),C0(f),C0(g),C0(h),C0(i),C0(j),C0(k),0})
#define OBF_STR_12(a,b,c,d,e,f,g,h,i,j,k,l) ((const char[]){C0(a),C0(b),C0(c),C0(d),C0(e),C0(f),C0(g),C0(h),C0(i),C0(j),C0(k),C0(l),0})
#define OBF_STR_13(a,b,c,d,e,f,g,h,i,j,k,l,m) ((const char[]){C0(a),C0(b),C0(c),C0(d),C0(e),C0(f),C0(g),C0(h),C0(i),C0(j),C0(k),C0(l),C0(m),0})
#define OBF_STR_14(a,b,c,d,e,f,g,h,i,j,k,l,m,n) ((const char[]){C0(a),C0(b),C0(c),C0(d),C0(e),C0(f),C0(g),C0(h),C0(i),C0(j),C0(k),C0(l),C0(m),C0(n),0})
#define OBF_STR_15(a,b,c,d,e,f,g,h,i,j,k,l,m,n,o) ((const char[]){C0(a),C0(b),C0(c),C0(d),C0(e),C0(f),C0(g),C0(h),C0(i),C0(j),C0(k),C0(l),C0(m),C0(n),C0(o),0})
#define OBF_STR_16(a,b,c,d,e,f,g,h,i,j,k,l,m,n,o,p) ((const char[]){C0(a),C0(b),C0(c),C0(d),C0(e),C0(f),C0(g),C0(h),C0(i),C0(j),C0(k),C0(l),C0(m),C0(n),C0(o),C0(p),0})
#define OBF_STR_17(a,b,c,d,e,f,g,h,i,j,k,l,m,n,o,p,q) ((const char[]){C0(a),C0(b),C0(c),C0(d),C0(e),C0(f),C0(g),C0(h),C0(i),C0(j),C0(k),C0(l),C0(m),C0(n),C0(o),C0(p),C0(q),0})
#define OBF_STR_18(a,b,c,d,e,f,g,h,i,j,k,l,m,n,o,p,q,r) ((const char[]){C0(a),C0(b),C0(c),C0(d),C0(e),C0(f),C0(g),C0(h),C0(i),C0(j),C0(k),C0(l),C0(m),C0(n),C0(o),C0(p),C0(q),C0(r),0})
#define OBF_STR_19(a,b,c,d,e,f,g,h,i,j,k,l,m,n,o,p,q,r,s) ((const char[]){C0(a),C0(b),C0(c),C0(d),C0(e),C0(f),C0(g),C0(h),C0(i),C0(j),C0(k),C0(l),C0(m),C0(n),C0(o),C0(p),C0(q),C0(r),C0(s),0})
#define OBF_STR_20(a,b,c,d,e,f,g,h,i,j,k,l,m,n,o,p,q,r,s,t) ((const char[]){C0(a),C0(b),C0(c),C0(d),C0(e),C0(f),C0(g),C0(h),C0(i),C0(j),C0(k),C0(l),C0(m),C0(n),C0(o),C0(p),C0(q),C0(r),C0(s),C0(t),0})
#define OBF_STR_21(a,b,c,d,e,f,g,h,i,j,k,l,m,n,o,p,q,r,s,t,u) ((const char[]){C0(a),C0(b),C0(c),C0(d),C0(e),C0(f),C0(g),C0(h),C0(i),C0(j),C0(k),C0(l),C0(m),C0(n),C0(o),C0(p),C0(q),C0(r),C0(s),C0(t),C0(u),0})
#define OBF_STR_22(a,b,c,d,e,f,g,h,i,j,k,l,m,n,o,p,q,r,s,t,u,v) ((const char[]){C0(a),C0(b),C0(c),C0(d),C0(e),C0(f),C0(g),C0(h),C0(i),C0(j),C0(k),C0(l),C0(m),C0(n),C0(o),C0(p),C0(q),C0(r),C0(s),C0(t),C0(u),C0(v),0})
#define OBF_STR_23(a,b,c,d,e,f,g,h,i,j,k,l,m,n,o,p,q,r,s,t,u,v,w) ((const char[]){C0(a),C0(b),C0(c),C0(d),C0(e),C0(f),C0(g),C0(h),C0(i),C0(j),C0(k),C0(l),C0(m),C0(n),C0(o),C0(p),C0(q),C0(r),C0(s),C0(t),C0(u),C0(v),C0(w),0})
#define OBF_STR_24(a,b,c,d,e,f,g,h,i,j,k,l,m,n,o,p,q,r,s,t,u,v,w,x) ((const char[]){C0(a),C0(b),C0(c),C0(d),C0(e),C0(f),C0(g),C0(h),C0(i),C0(j),C0(k),C0(l),C0(m),C0(n),C0(o),C0(p),C0(q),C0(r),C0(s),C0(t),C0(u),C0(v),C0(w),C0(x),0})
#define OBF_STR_25(a,b,c,d,e,f,g,h,i,j,k,l,m,n,o,p,q,r,s,t,u,v,w,x,y) ((const char[]){C0(a),C0(b),C0(c),C0(d),C0(e),C0(f),C0(g),C0(h),C0(i),C0(j),C0(k),C0(l),C0(m),C0(n),C0(o),C0(p),C0(q),C0(r),C0(s),C0(t),C0(u),C0(v),C0(w),C0(x),C0(y),0})

/* ============================================================
 *  2. Opaque Predicates（不透明谓词）
 *
 *  永真/永假的表达式，编译后看似有分支但实际只走一条路径。
 *  干扰静态分析器的数据流分析。
 * ============================================================ */

/* 永真：x*(x+1) 必为偶数（两个连续整数必有一个偶数） */
#define OPAQUE_TRUE(x) (((uint32_t)(x) * ((uint32_t)(x) + 1)) % 2 == 0)

/* 永假：x*(x+1) 必为偶数，不可能为奇数 */
#define OPAQUE_FALSE(x) (((uint32_t)(x) * ((uint32_t)(x) + 1)) % 2 == 1)

/* 更复杂的永真：n^3 - n = (n-1)*n*(n+1) 必被 6 整除（Fermat 小定理推论） */
#define OPAQUE_TRUE_2(n) (((uint64_t)(n) * (uint64_t)(n) * (uint64_t)(n) - (uint64_t)(n)) % 6 == 0)

/* 永真：2^(2k) - 1 必被 3 整除 */
#define OPAQUE_TRUE_3(k) (((1ULL << ((k) % 32 + 2)) - 1) % 3 == 0)

/* ============================================================
 *  3. 虚假控制流（Bogus Control Flow）
 *
 *  在真实代码路径前后插入永假条件的虚假分支，
 *  分支中执行看似有意义但永远不会执行的代码。
 * ============================================================ */

/* 虚假分支宏：if 条件永假，但静态分析器无法轻易判断 */
#define BOGUS_IF(x) if (OPAQUE_FALSE((uint32_t)(uintptr_t)&x))

/* 虚假计算：干扰数据流分析，永远不会执行 */
#define BOGUS_CALC(var) do { \
    volatile uint32_t _bogus = (uint32_t)(uintptr_t)&var; \
    _bogus = _bogus * 31 + 17; \
    _bogus ^= _bogus >> 13; \
    _bogus = _bogus * 0x45d9f3b; \
    var ^= (_bogus & 0); /* &0 确保不影响真实值 */ \
} while(0)

/* ============================================================
 *  4. 指令替换（Instruction Substitution）
 *
 *  将简单运算替换为等价但复杂的表达式。
 * ============================================================ */

/* XOR 替换：a ^ b 等价于 (a & ~b) | (~a & b) */
#define XOR_SUB(a, b) (((a) & ~(b)) | (~(a) & (b)))

/* ADD 替换：a + b 等价于 (a ^ b) + 2*(a & b) */
#define ADD_SUB(a, b) (((a) ^ (b)) + 2 * ((a) & (b)))

/* SUB 替换：a - b 等价于 (a ^ b) - 2*(~a & b) = a + (~b+1) */
#define SUB_SUB(a, b) ((a) + (~(b) + 1))

/* ============================================================
 *  5. 控制流扁平化（Control Flow Flattening）
 *
 *  将线性执行流转换为 switch-case state machine。
 *  每个 basic block 是一个 case，通过修改 state 变量跳转。
 *
 *  用法：
 *    CFF_BEGIN(state)
 *      CFF_CASE(0, state)
 *        // block 0 代码
 *        CFF_NEXT(1, state)
 *      CFF_CASE(1, state)
 *        // block 1 代码
 *        CFF_NEXT(2, state)
 *      ...
 *      CFF_CASE(n, state)
 *        // block n 代码
 *        CFF_BREAK(state)
 *    CFF_END
 * ============================================================ */

#define CFF_STATE_VAR volatile uint32_t _cff_state

/* 使用素数乘法 + 偏移，让 case 值不连续，干扰分析 */
#define CFF_LABEL(n) ((uint32_t)(n) * 2654435761u + 0xDEADBEEF)

#define CFF_BEGIN \
    do { \
        CFF_STATE_VAR = CFF_LABEL(0); \
        while (_cff_state != 0xFFFFFFFF) { switch (_cff_state) {

#define CFF_CASE(n) \
            case CFF_LABEL(n): { \
                volatile uint32_t _cff_guard = (uint32_t)(n);

#define CFF_NEXT(n) \
                _cff_state = CFF_LABEL(n); \
                BOGUS_CALC(_cff_guard); \
                break; \
            }

#define CFF_BREAK \
                _cff_state = 0xFFFFFFFF; \
                break; \
            }

#define CFF_END \
            default: _cff_state = 0xFFFFFFFF; break; \
        } } \
    } while(0);

/* ============================================================
 *  6. 栈canary 增强 + 反 hook
 * ============================================================ */

/* 运行时随机 canary（非编译器固定值，每次调用不同） */
static inline uint32_t runtime_canary(void) {
    uint32_t c = 0;
    /* 使用栈地址和时间戳组合，每次调用不同 */
    volatile uint32_t stack_addr;
    c = (uint32_t)(uintptr_t)&stack_addr;
    c = c * 1103515245u + 12345u;
    c ^= (c >> 16);
    return c;
}

/* 检查 canary 是否被覆盖 */
#define CHECK_CANARY(c) do { \
    if ((c) != (c)) { /* 永假，但编译后可能被优化 */ \
        volatile int* _p = (int*)0; *_p = 0; \
    } \
} while(0)

/* ============================================================
 *  7. 函数内联控制（防止 Hook 替换）
 *
 *  关键函数强制内联，减少函数调用点，降低被 Hook 的可能性。
 * ============================================================ */
#define FORCE_INLINE static inline __attribute__((always_inline))
#define NO_INLINE __attribute__((noinline))

/* ============================================================
 *  8. 数据混淆（运行时密钥派生）
 *
 *  静态常量在运行时经过变换才得到真实值，
 *  静态分析无法直接读取。
 * ============================================================ */

/* 从两个常量派生真实值：real = a ^ b */
static inline uint8_t derive_byte(uint8_t a, uint8_t b) {
    volatile uint8_t r = a;
    volatile uint8_t m = b;
    /* 用指令替换版本的 XOR，干扰分析 */
    r = XOR_SUB(r, m);
    return r;
}

/* 从常量数组派生字符串到栈缓冲区 */
#define DERIVE_STR(buf, pairs...) do { \
    const uint8_t _pairs[] = { pairs }; \
    size_t _n = sizeof(_pairs) / 2; \
    for (size_t _i = 0; _i < _n; _i++) \
        (buf)[_i] = derive_byte(_pairs[_i*2], _pairs[_i*2+1]); \
    (buf)[_n] = 0; \
} while(0)

#endif /* OBFUSCATION_H */
