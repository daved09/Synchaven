//! Reads the application version from `version.properties` (entry `version=…`) and provides it
//! to the program as `SYNCHAVEN_VERSION` (`env!("SYNCHAVEN_VERSION")`).
//!
//! The file is looked up next to `Cargo.toml` and one directory above it (project root). The version in
//! `Cargo.toml` is only a placeholder.
//!
//! Also makes Cargo rebuild when the translations in `locales/` change.

use std::path::PathBuf;

fn main() {
    let manifest_dir = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap());
    let candidates = [manifest_dir.join("version.properties"), manifest_dir.join("../version.properties")];

    for path in &candidates {
        // Also react to files that don't exist yet, so Cargo rebuilds as soon as they appear
        println!("cargo:rerun-if-changed={}", path.display());
    }
    // The texts in locales/ are embedded by rust-i18n at compile time; rebuild when they change.
    println!("cargo:rerun-if-changed={}", manifest_dir.join("locales").display());

    let path = candidates
        .iter()
        .find(|p| p.is_file())
        .unwrap_or_else(|| panic!("version.properties not found (searched: {candidates:?})"));
    let content = std::fs::read_to_string(path)
        .unwrap_or_else(|e| panic!("Cannot read {}: {e}", path.display()));

    let version = content
        .lines()
        .map(str::trim)
        .filter(|l| !l.starts_with('#') && !l.starts_with('!'))
        .find_map(|l| {
            let (key, value) = l.split_once(['=', ':'])?;
            (key.trim() == "version").then(|| value.trim().to_string())
        })
        .filter(|v| !v.is_empty())
        .unwrap_or_else(|| panic!("No 'version=…' entry in {}", path.display()));

    println!("cargo:rustc-env=SYNCHAVEN_VERSION={version}");
}
