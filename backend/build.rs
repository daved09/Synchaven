//! Liest die Anwendungsversion aus `version.properties` (Eintrag `version=…`) und stellt sie
//! dem Programm als `SYNCHAVEN_VERSION` zur Verfügung (`env!("SYNCHAVEN_VERSION")`).
//!
//! Gesucht wird neben `Cargo.toml` und ein Verzeichnis darüber (Projektwurzel). Die Version in
//! `Cargo.toml` ist nur ein Platzhalter.

use std::path::PathBuf;

fn main() {
    let manifest_dir = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap());
    let candidates = [manifest_dir.join("version.properties"), manifest_dir.join("../version.properties")];

    for path in &candidates {
        // Auch auf noch fehlende Dateien reagieren, damit Cargo neu baut, sobald sie auftauchen
        println!("cargo:rerun-if-changed={}", path.display());
    }

    let path = candidates
        .iter()
        .find(|p| p.is_file())
        .unwrap_or_else(|| panic!("version.properties nicht gefunden (gesucht: {candidates:?})"));
    let content = std::fs::read_to_string(path)
        .unwrap_or_else(|e| panic!("{} kann nicht gelesen werden: {e}", path.display()));

    let version = content
        .lines()
        .map(str::trim)
        .filter(|l| !l.starts_with('#') && !l.starts_with('!'))
        .find_map(|l| {
            let (key, value) = l.split_once(['=', ':'])?;
            (key.trim() == "version").then(|| value.trim().to_string())
        })
        .filter(|v| !v.is_empty())
        .unwrap_or_else(|| panic!("Kein Eintrag 'version=…' in {}", path.display()));

    println!("cargo:rustc-env=SYNCHAVEN_VERSION={version}");
}
