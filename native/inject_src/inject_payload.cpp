#include <cstdint>

/**
 * Standalone native payload compiled into `inject.so` OUTSIDE the APK.
 * Contains a fixed-layout binary data record (`DLINJECT`) embedded in `.rodata`
 * so the Android app can verify ELF64-AArch64 integrity and extract runtime
 * operational parameters after downloading `inject.so` from the PHP Backend,
 * without violating Android 14–16 SELinux W^X restrictions on dlopen().
 */
struct __attribute__((packed)) DigitalLockInjectRecord {
    char magic[8];              // "DLINJ001"
    uint32_t structVersion;     // 1
    uint32_t minAppVersion;     // 1
    float defaultRefraction;    // 1.05f
    float defaultAberration;    // 1.00f
    float defaultFresnel;       // 0.72f
    uint32_t flags;             // bit 0 = allow_lock_engine, bit 1 = allow_glass_fx
};

extern "C" {
__attribute__((visibility("default"), used))
const DigitalLockInjectRecord kDigitalLockInjectData = {
    {'D', 'L', 'I', 'N', 'J', '0', '0', '1'},
    1U,
    1U,
    1.05f,
    1.00f,
    0.72f,
    0x00000003U,
};
}
