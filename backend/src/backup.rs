//! Export and import of the whole database as JSON (`synchaven export` / `synchaven import`).
//!
//! Intended as a backup before updates: if a migration fails, the old database is set aside,
//! and on import the new version creates an empty database with the current schema and loads the data.
//!
//! The format is deliberately schema-independent: each table is a list of objects with column names.
//! On import, only tables and columns that exist in the current schema are taken over; missing columns
//! get their default value. Anything skipped is reported as a warning.
//!
//! The events in the birthday calendar are not exported: they are derived from the contacts and are
//! regenerated on import. The birthday calendar itself (color, visibility, reminders) stays in the export.
//!
//! Push subscriptions are missing as well (`NOT_EXPORTED`): they belong to individual browsers and to the VAPID key
//! in the data directory. The web UI re-registers a subscription that is still enabled the next time it is opened.

use std::{collections::BTreeMap, io::Write, path::Path, str::FromStr, time::Duration};

use anyhow::{bail, Context};
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
use sqlx::{
    sqlite::{SqliteConnectOptions, SqliteJournalMode, SqlitePool, SqlitePoolOptions},
    Column, Row, SqliteConnection, TypeInfo, ValueRef,
};

const FORMAT: &str = "synchaven-export";

/// Tables that are neither exported nor imported (not even from older exports that contain them)
const NOT_EXPORTED: &[&str] = &["push_subscriptions"];
const FORMAT_VERSION: u32 = 1;

#[derive(Serialize, Deserialize)]
pub struct Export {
    format: String,
    format_version: u32,
    app_version: String,
    exported_at: String,
    /// Last applied migration of the exported database (for information only)
    migration: Option<i64>,
    tables: BTreeMap<String, Vec<Map<String, Value>>>,
}

const USAGE: &str = "Verwendung:
  synchaven export [DATEI]             Datenbank als JSON exportieren (ohne DATEI auf die Standardausgabe)
  synchaven import [--replace] DATEI   JSON-Export importieren (DATEI \"-\" = Standardeingabe)

Die Datenbank liegt in $DATA_DIR/synchaven.db. Der Import legt sie bei Bedarf an und bringt sie auf
das aktuelle Schema. Er verweigert eine Datenbank, die schon Daten enthält – außer mit --replace, das
vorher alle vorhandenen Daten löscht. Während des Imports darf der Server nicht laufen.";

/// Runs `export`/`import`. `None` if `cmd` is neither of these commands.
pub async fn run_cli(cmd: &str, args: &[String]) -> Option<anyhow::Result<()>> {
    match cmd {
        "export" => Some(export_cli(args).await),
        "import" => Some(import_cli(args).await),
        _ => None,
    }
}

async fn open(create: bool) -> anyhow::Result<SqlitePool> {
    let path = crate::config::data_dir_from_env().join("synchaven.db");
    if !create && !path.is_file() {
        bail!("Keine Datenbank unter {} gefunden (DATA_DIR prüfen)", path.display());
    }
    if create {
        if let Some(dir) = path.parent() {
            std::fs::create_dir_all(dir).with_context(|| format!("{} kann nicht angelegt werden", dir.display()))?;
        }
    }
    let options = SqliteConnectOptions::from_str(&format!("sqlite://{}", path.display()))?
        .create_if_missing(create)
        .foreign_keys(true)
        .journal_mode(SqliteJournalMode::Wal)
        .busy_timeout(Duration::from_secs(10));
    SqlitePoolOptions::new()
        .max_connections(1)
        .connect_with(options)
        .await
        .with_context(|| format!("Datenbank {} kann nicht geöffnet werden", path.display()))
}

/// The application's tables in creation order (without SQLite-internal and sqlx tables).
async fn tables(conn: &mut SqliteConnection) -> anyhow::Result<Vec<String>> {
    Ok(sqlx::query_scalar(
        "SELECT name FROM sqlite_master WHERE type = 'table' \
         AND name NOT LIKE 'sqlite_%' AND name NOT LIKE '_sqlx_%' ORDER BY rowid",
    )
    .fetch_all(conn)
    .await?)
}

async fn columns(conn: &mut SqliteConnection, table: &str) -> anyhow::Result<Vec<String>> {
    Ok(sqlx::query_scalar("SELECT name FROM pragma_table_info(?) ORDER BY cid").bind(table).fetch_all(conn).await?)
}

fn quote(ident: &str) -> String {
    format!("\"{}\"", ident.replace('"', "\"\""))
}

async fn last_migration(conn: &mut SqliteConnection) -> Option<i64> {
    sqlx::query_scalar("SELECT MAX(version) FROM _sqlx_migrations WHERE success = 1").fetch_one(conn).await.ok().flatten()
}

/// Reads all tables in one read transaction – a consistent state, even while the server is running.
pub async fn export_db(db: &SqlitePool) -> anyhow::Result<(Export, Vec<String>)> {
    let mut tx = db.begin().await?;
    let mut tables_out = BTreeMap::new();
    let mut summary = Vec::new();
    for table in tables(&mut tx).await? {
        if NOT_EXPORTED.contains(&table.as_str()) {
            continue;
        }
        let filter = if table == "events" {
            // Birthday events are regenerated from the contacts on import
            format!(" WHERE calendar_id NOT IN (SELECT id FROM calendars WHERE kind = '{}')", crate::birthdays::KIND)
        } else {
            String::new()
        };
        let rows = sqlx::query(&format!("SELECT * FROM {}{filter}", quote(&table))).fetch_all(&mut *tx).await?;
        let mut out = Vec::with_capacity(rows.len());
        for row in &rows {
            let mut obj = Map::new();
            for (i, col) in row.columns().iter().enumerate() {
                let raw = row.try_get_raw(i)?;
                let value = if raw.is_null() {
                    Value::Null
                } else {
                    match raw.type_info().name() {
                        "INTEGER" => Value::from(row.try_get::<i64, _>(i)?),
                        "REAL" => Value::from(row.try_get::<f64, _>(i)?),
                        "TEXT" => Value::from(row.try_get::<String, _>(i)?),
                        other => bail!("Tabelle {table}, Spalte {}: Typ {other} wird nicht unterstützt", col.name()),
                    }
                };
                obj.insert(col.name().to_string(), value);
            }
            out.push(obj);
        }
        summary.push(format!("{table}: {}", out.len()));
        tables_out.insert(table, out);
    }
    let export = Export {
        format: FORMAT.into(),
        format_version: FORMAT_VERSION,
        app_version: env!("SYNCHAVEN_VERSION").into(),
        exported_at: chrono::Utc::now().format("%Y-%m-%dT%H:%M:%SZ").to_string(),
        migration: last_migration(&mut tx).await,
        tables: tables_out,
    };
    tx.rollback().await?;
    Ok((export, summary))
}

async fn export_cli(args: &[String]) -> anyhow::Result<()> {
    let target = match args {
        [] => None,
        [file] if file == "-" => None,
        [file] => Some(file.as_str()),
        _ => bail!("{USAGE}"),
    };
    let db = open(false).await?;
    let (export, summary) = export_db(&db).await?;
    db.close().await;

    let json = serde_json::to_vec_pretty(&export)?;
    match target {
        Some(file) => {
            // Write to a temporary file first, so an abort doesn't leave a half-written export behind
            let tmp = format!("{file}.tmp");
            std::fs::write(&tmp, &json).with_context(|| format!("{tmp} kann nicht geschrieben werden"))?;
            std::fs::rename(&tmp, file).with_context(|| format!("{file} kann nicht geschrieben werden"))?;
            eprintln!("Export nach {file} geschrieben ({})", summary.join(", "));
        }
        None => {
            let mut stdout = std::io::stdout().lock();
            stdout.write_all(&json)?;
            stdout.write_all(b"\n")?;
            stdout.flush()?;
            eprintln!("Export abgeschlossen ({})", summary.join(", "));
        }
    }
    Ok(())
}

async fn import_cli(args: &[String]) -> anyhow::Result<()> {
    let mut replace = false;
    let mut file = None;
    for arg in args {
        match arg.as_str() {
            "--replace" => replace = true,
            _ if file.is_none() => file = Some(arg.as_str()),
            _ => bail!("{USAGE}"),
        }
    }
    let Some(file) = file else { bail!("{USAGE}") };

    let json = if file == "-" {
        let mut buf = Vec::new();
        std::io::Read::read_to_end(&mut std::io::stdin().lock(), &mut buf)?;
        buf
    } else {
        std::fs::read(Path::new(file)).with_context(|| format!("{file} kann nicht gelesen werden"))?
    };
    let export = parse(&json)?;
    eprintln!(
        "Importiere Export von Synchaven {} vom {} (Migration {})",
        export.app_version,
        export.exported_at,
        export.migration.map_or("?".into(), |m| m.to_string())
    );

    let db = open(true).await?;
    sqlx::migrate!("./migrations").run(&db).await.context(
        "Migration fehlgeschlagen – für den Import die bestehende Datenbank zur Seite legen \
         (synchaven.db, synchaven.db-wal, synchaven.db-shm umbenennen), dann wird eine neue angelegt",
    )?;
    let report = import_db(&db, &export, replace).await?;
    for w in &report.warnings {
        eprintln!("Warnung: {w}");
    }
    eprintln!("Import abgeschlossen ({})", report.summary.join(", "));
    Ok(())
}

/// Reads and validates an export.
pub fn parse(json: &[u8]) -> Result<Export, ImportError> {
    let export: Export = serde_json::from_slice(json)
        .map_err(|e| ImportError::Invalid(format!("Datei ist kein gültiger Synchaven-Export: {e}")))?;
    if export.format != FORMAT {
        return Err(ImportError::Invalid(format!("Datei ist kein Synchaven-Export (format = {:?})", export.format)));
    }
    if export.format_version > FORMAT_VERSION {
        return Err(ImportError::Invalid(format!(
            "Exportformat {} ist neuer als diese Version unterstützt ({FORMAT_VERSION})",
            export.format_version
        )));
    }
    Ok(export)
}

#[derive(Debug, Serialize)]
pub struct ImportReport {
    /// Number of imported entries per table, e.g. "events: 12"
    pub summary: Vec<String>,
    /// Skipped tables and columns
    pub warnings: Vec<String>,
}

#[derive(Debug, thiserror::Error)]
pub enum ImportError {
    /// File unusable or data doesn't fit (e.g. violated foreign keys)
    #[error("{0}")]
    Invalid(String),
    /// The database already contains data and `replace` is not set
    #[error("{0}")]
    NotEmpty(String),
    #[error(transparent)]
    Other(#[from] anyhow::Error),
}

impl From<sqlx::Error> for ImportError {
    fn from(e: sqlx::Error) -> Self {
        Self::Other(e.into())
    }
}

/// Loads an export in a single transaction: all or nothing.
/// The schema must already be current (migrations have run).
pub async fn import_db(db: &SqlitePool, export: &Export, replace: bool) -> Result<ImportReport, ImportError> {
    let mut conn = db.acquire().await?;
    // IMMEDIATE: take the write lock right away so the running server doesn't write in between
    sqlx::query("BEGIN IMMEDIATE").execute(&mut *conn).await?;
    match import_rows(&mut conn, export, replace).await {
        Ok(report) => match sqlx::query("COMMIT").execute(&mut *conn).await {
            Ok(_) => Ok(report),
            Err(e) => {
                let _ = sqlx::query("ROLLBACK").execute(&mut *conn).await;
                Err(ImportError::Invalid(format!("Import abgebrochen, die Daten sind nicht stimmig: {e}")))
            }
        },
        Err(e) => {
            let _ = sqlx::query("ROLLBACK").execute(&mut *conn).await;
            Err(e)
        }
    }
}

async fn import_rows(conn: &mut SqliteConnection, export: &Export, replace: bool) -> Result<ImportReport, ImportError> {
    let mut warnings = Vec::new();
    sqlx::query("PRAGMA defer_foreign_keys = ON").execute(&mut *conn).await?;
    let target_tables = tables(conn).await?;

    let mut non_empty = Vec::new();
    for table in &target_tables {
        let n: i64 = sqlx::query_scalar(&format!("SELECT COUNT(*) FROM {}", quote(table))).fetch_one(&mut *conn).await?;
        if n > 0 {
            non_empty.push(format!("{table}: {n}"));
        }
    }
    if !non_empty.is_empty() {
        if !replace {
            return Err(ImportError::NotEmpty(format!(
                "Die Datenbank enthält bereits Daten ({}). Sie müssen vor dem Import gelöscht werden (Kommandozeile: --replace).",
                non_empty.join(", ")
            )));
        }
        for table in &target_tables {
            sqlx::query(&format!("DELETE FROM {}", quote(table))).execute(&mut *conn).await?;
        }
    }

    for table in export.tables.keys().filter(|t| !target_tables.contains(t) && !NOT_EXPORTED.contains(&t.as_str())) {
        warnings.push(format!("Tabelle {table} gibt es nicht mehr – {} Einträge übersprungen", export.tables[table].len()));
    }

    let mut summary = Vec::new();
    for table in target_tables.iter().filter(|t| !NOT_EXPORTED.contains(&t.as_str())) {
        let Some(rows) = export.tables.get(table) else {
            warnings.push(format!("Tabelle {table} fehlt im Export"));
            continue;
        };
        let target_cols = columns(conn, table).await?;
        let mut skipped = std::collections::BTreeSet::new();
        for (n, row) in rows.iter().enumerate() {
            let cols: Vec<&String> = row.keys().filter(|c| target_cols.contains(c)).collect();
            skipped.extend(row.keys().filter(|c| !target_cols.contains(c)).cloned());
            if cols.is_empty() {
                continue;
            }
            let sql = format!(
                "INSERT INTO {} ({}) VALUES ({})",
                quote(table),
                cols.iter().map(|c| quote(c)).collect::<Vec<_>>().join(", "),
                vec!["?"; cols.len()].join(", ")
            );
            let mut query = sqlx::query(&sql);
            for col in &cols {
                query = match &row[col.as_str()] {
                    Value::Null => query.bind(None::<String>),
                    Value::Bool(b) => query.bind(*b as i64),
                    Value::Number(num) => match num.as_i64() {
                        Some(i) => query.bind(i),
                        None => query.bind(num.as_f64()),
                    },
                    Value::String(s) => query.bind(s.as_str()),
                    // Should not happen; store as JSON text instead of aborting
                    other => query.bind(other.to_string()),
                };
            }
            query
                .execute(&mut *conn)
                .await
                .map_err(|e| ImportError::Invalid(format!("Tabelle {table}, Eintrag {}: Einfügen fehlgeschlagen: {e}", n + 1)))?;
        }
        if !skipped.is_empty() {
            warnings.push(format!(
                "Spalten von {table} gibt es nicht mehr, ihre Werte wurden übersprungen: {}",
                skipped.into_iter().collect::<Vec<_>>().join(", ")
            ));
        }
        summary.push(format!("{table}: {}", rows.len()));
    }

    // Older exports don't know is_admin yet: then, as in the migration, the oldest account becomes administrator.
    let has_admin: Option<i64> = sqlx::query_scalar("SELECT 1 FROM users WHERE is_admin = 1 LIMIT 1")
        .fetch_optional(&mut *conn)
        .await?;
    if has_admin.is_none() {
        let promoted = sqlx::query(
            "UPDATE users SET is_admin = 1 WHERE id = (SELECT id FROM users ORDER BY created_at, rowid LIMIT 1)",
        )
        .execute(&mut *conn)
        .await?;
        if promoted.rows_affected() > 0 {
            warnings.push("Der Export enthielt keinen Administrator – das älteste Konto ist jetzt Administrator".into());
        }
    }

    // Generate birthday events from the contacts (older exports already contain them, in which case they stay)
    let contacts: Vec<String> = sqlx::query_scalar("SELECT id FROM contacts").fetch_all(&mut *conn).await?;
    for id in &contacts {
        crate::birthdays::sync_contact(conn, id)
            .await
            .map_err(|e| ImportError::Other(anyhow::anyhow!("Geburtstagskalender: {e}")))?;
    }
    let birthdays: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM events WHERE contact_id IS NOT NULL")
        .fetch_one(&mut *conn)
        .await?;
    summary.push(format!("Geburtstage: {birthdays}"));

    Ok(ImportReport { summary, warnings })
}
