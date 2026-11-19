//! DigitalLock Rust Security Engine (`libdigitallock_security.so`)
//!
//! Responsibilities:
//! - SHA-256 hashing & verification of resource bundles and integrity artifacts
//! - Strict Ed25519 signature verification (`ed25519-dalek`) for server manifests & policies
//! - Memory-safe untrusted JSON manifest schema validation & anti-path-traversal enforcement
//! - Constant-time byte comparison (`subtle`)

use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine;
use ed25519_dalek::{Signature, VerifyingKey};
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use serde::Deserialize;
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;

const ENGINE_VERSION: &str = "digitallock-rust-security/2.0.0";
const MAX_MANIFEST_BYTES: usize = 512 * 1024;
const MAX_RESOURCE_COUNT: usize = 64;
const MAX_SINGLE_RESOURCE_BYTES: u64 = 15 * 1024 * 1024;

#[derive(Deserialize)]
struct ManifestResourceEntry {
    id: String,
    #[serde(rename = "type")]
    res_type: String,
    path: String,
    sha256: String,
    size: u64,
}

#[derive(Deserialize)]
struct RemoteManifestSchema {
    schema_version: u32,
    version_code: u64,
    version_name: String,
    min_app_version_code: u32,
    #[serde(default)]
    revoked: bool,
    lockscreen: serde_json::Value,
    pin_screen: serde_json::Value,
    resources: Vec<ManifestResourceEntry>,
}

pub fn sha256_hex_bytes(data: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(data);
    hex::encode(hasher.finalize())
}

pub fn verify_ed25519_raw(message: &[u8], signature_b64: &str, public_key_b64: &str) -> bool {
    let sig_bytes = match BASE64.decode(signature_b64.trim()) {
        Ok(b) if b.len() == 64 => b,
        _ => return false,
    };
    let pub_raw = match BASE64.decode(public_key_b64.trim()) {
        Ok(b) => b,
        Err(_) => return false,
    };
    // Accept either 32-byte raw Ed25519 public key or 44-byte X.509 SPKI DER (12-byte header + 32-byte key)
    let key_slice: &[u8] = if pub_raw.len() == 32 {
        &pub_raw
    } else if pub_raw.len() == 44 && pub_raw[..12] == [0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00] {
        &pub_raw[12..]
    } else {
        return false;
    };

    let key_arr: [u8; 32] = match key_slice.try_into() {
        Ok(a) => a,
        Err(_) => return false,
    };
    let sig_arr: [u8; 64] = match sig_bytes.try_into() {
        Ok(a) => a,
        Err(_) => return false,
    };

    let verifying_key = match VerifyingKey::from_bytes(&key_arr) {
        Ok(k) => k,
        Err(_) => return false,
    };
    let signature = Signature::from_bytes(&sig_arr);
    verifying_key.verify_strict(message, &signature).is_ok()
}

pub fn validate_manifest_json_str(raw_json: &str) -> Result<(), &'static str> {
    if raw_json.is_empty() || raw_json.len() > MAX_MANIFEST_BYTES {
        return Err("manifest_size_out_of_bounds");
    }
    let parsed: RemoteManifestSchema =
        serde_json::from_str(raw_json).map_err(|_| "manifest_json_invalid")?;

    if parsed.schema_version != 1 {
        return Err("unsupported_schema_version");
    }
    if parsed.version_code == 0 || parsed.version_name.trim().is_empty() || parsed.version_name.len() > 32 {
        return Err("invalid_version_metadata");
    }
    if parsed.min_app_version_code == 0 {
        return Err("invalid_min_app_version");
    }
    if parsed.revoked {
        return Err("manifest_revoked");
    }
    if !parsed.lockscreen.is_object() || !parsed.pin_screen.is_object() {
        return Err("missing_ui_sections");
    }
    if parsed.resources.len() > MAX_RESOURCE_COUNT {
        return Err("too_many_resources");
    }

    let allowed_types = [
        "theme",
        "wallpaper",
        "animation",
        "shader_config",
        "icon",
        "font",
        "sound",
        "config",
    ];
    let forbidden_exts = [".dex", ".jar", ".apk", ".sh", ".php", ".exe"];

    for res in &parsed.resources {
        if res.id.is_empty() || res.id.len() > 64 {
            return Err("invalid_resource_id");
        }
        if !allowed_types.contains(&res.res_type.as_str()) {
            return Err("disallowed_resource_type");
        }
        let path_lower = res.path.to_ascii_lowercase();
        if res.path.is_empty()
            || res.path.starts_with('/')
            || res.path.contains("..")
            || res.path.contains('\\')
            || res.path.contains('\0')
        {
            return Err("unsafe_resource_path");
        }
        if forbidden_exts.iter().any(|ext| path_lower.ends_with(ext)) {
            return Err("executable_resource_forbidden");
        }
        if res.sha256.len() != 64 || !res.sha256.bytes().all(|b| b.is_ascii_hexdigit()) {
            return Err("invalid_resource_sha256");
        }
        if res.size == 0 || res.size > MAX_SINGLE_RESOURCE_BYTES {
            return Err("resource_size_out_of_bounds");
        }
    }

    Ok(())
}

#[no_mangle]
pub extern "system" fn Java_com_digitallock_security_NativeSecurityBridge_nativeGetEngineVersion(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    env.new_string(ENGINE_VERSION)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_digitallock_security_NativeSecurityBridge_nativeSha256Hex(
    env: JNIEnv,
    _class: JClass,
    input: JByteArray,
) -> jstring {
    let bytes = match env.convert_byte_array(input) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let digest = sha256_hex_bytes(&bytes);
    env.new_string(digest)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_digitallock_security_NativeSecurityBridge_nativeVerifyEd25519(
    mut env: JNIEnv,
    _class: JClass,
    message: JByteArray,
    signature_b64: JString,
    public_key_b64: JString,
) -> jboolean {
    let msg_bytes = match env.convert_byte_array(message) {
        Ok(b) => b,
        Err(_) => return JNI_FALSE,
    };
    let sig_str: String = match env.get_string(&signature_b64) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    let pub_str: String = match env.get_string(&public_key_b64) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };
    if verify_ed25519_raw(&msg_bytes, &sig_str, &pub_str) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_digitallock_security_NativeSecurityBridge_nativeValidateManifestJson(
    mut env: JNIEnv,
    _class: JClass,
    manifest_json: JString,
) -> jstring {
    let raw: String = match env.get_string(&manifest_json) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let status = match validate_manifest_json_str(&raw) {
        Ok(()) => "OK",
        Err(code) => code,
    };
    env.new_string(status)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_digitallock_security_NativeSecurityBridge_nativeConstantTimeEquals(
    env: JNIEnv,
    _class: JClass,
    a: JByteArray,
    b: JByteArray,
) -> jboolean {
    let bytes_a = match env.convert_byte_array(a) {
        Ok(v) => v,
        Err(_) => return JNI_FALSE,
    };
    let bytes_b = match env.convert_byte_array(b) {
        Ok(v) => v,
        Err(_) => return JNI_FALSE,
    };
    if bytes_a.len() == bytes_b.len() && bool::from(bytes_a.ct_eq(&bytes_b)) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_sha256_known_vector() {
        let digest = sha256_hex_bytes(b"digitallock");
        assert_eq!(digest.len(), 64);
        assert_eq!(digest, "86d32ccfa7a9263fa3b0c37d3eb047d4710e6531b35286d541c81fcf8f01ec46");
    }

    #[test]
    fn test_manifest_rejects_path_traversal_and_dex() {
        let bad = r#"{"schema_version":1,"version_code":1,"version_name":"1.0","min_app_version_code":1,"lockscreen":{},"pin_screen":{},"resources":[{"id":"1","type":"wallpaper","path":"../secret.pem","sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","size":100}]}"#;
        assert_eq!(validate_manifest_json_str(bad), Err("unsafe_resource_path"));

        let bad_dex = r#"{"schema_version":1,"version_code":1,"version_name":"1.0","min_app_version_code":1,"lockscreen":{},"pin_screen":{},"resources":[{"id":"1","type":"config","path":"configs/a.dex","sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","size":100}]}"#;
        assert_eq!(validate_manifest_json_str(bad_dex), Err("executable_resource_forbidden"));
    }

    #[test]
    fn test_manifest_accepts_valid_payload() {
        let ok = r#"{"schema_version":1,"version_code":2,"version_name":"2.0.0","min_app_version_code":1,"lockscreen":{"status_prompt_text":"PIN"},"pin_screen":{"key_size_dp":82},"resources":[{"id":"bg","type":"wallpaper","path":"wallpapers/default.jpg","sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","size":2048}]}"#;
        assert_eq!(validate_manifest_json_str(ok), Ok(()));
    }
}
