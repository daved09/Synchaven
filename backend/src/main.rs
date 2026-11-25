mod auth;
mod backup;
mod birthdays;
mod config;
mod contacts;
mod dav;
mod error;
mod i18n;
mod ical;
mod logs;
mod models;
mod push;
mod recurrence;
mod reminders;
mod routes;
mod settings;
mod time;
mod vcard;

use std::{str::FromStr, sync::Arc, time::Duration};

use anyhow::Context;
use axum::{
    extract::DefaultBodyLimit,
    http::HeaderValue,
    routing::{any, get_service},
    Router,
};
use sqlx::sqlite::{SqliteConnectOptions, SqliteJournalMode, SqlitePool, SqlitePoolOptions};
use tower_http::{
    cors::{Any, CorsLayer},
    services::{ServeDir, ServeFile},
    trace::TraceLayer,
};

use crate::config::Config;

// Texts per language from locales/*.json, see the i18n module
rust_i18n::i18n!("locales", fallback = "en");

#[derive(Clone)]
pub struct AppState {
    pub db: SqlitePool,
    pub config: Arc<Config>,
    pub jwt: Arc<auth::JwtKeys>,
    pub dav_auth: Arc<dav::AuthCache>,
    pub push: Arc<push::WebPush>,
    /// Settings from the admin page (registration, session lifetime)
    pub settings: Arc<settings::SettingsStore>,
    /// Server start time (for the overview in the admin page)
    pub started_at: chrono::DateTime<chrono::Utc>,
    /// Recent log output (for the admin page)
    pub logs: &'static logs::LogBuffer,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if let Some(cmd) = args.first() {
        if cmd == "healthcheck" {
            return healthcheck().await;
        }
        if let Some(result) = backup::run_cli(cmd, &args[1..]).await {
            return result;
        }
    }
    let logs: &'static logs::LogBuffer = Box::leak(Box::default());
    {
        use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};
        tracing_subscriber::registry()
            .with(
                tracing_subscriber::EnvFilter::try_from_default_env()
                    .unwrap_or_else(|_| "synchaven=info,tower_http=warn".into()),
            )
            .with(tracing_subscriber::fmt::layer())
            .with(logs)
            .init();
    }

    tracing::info!("Synchaven {} starting", env!("SYNCHAVEN_VERSION"));
    let config = Config::from_env()?;
    tracing::info!("Database: {}", config.db_path().display());

    let options = SqliteConnectOptions::from_str(&format!("sqlite://{}", config.db_path().display()))?
        .create_if_missing(true)
        .foreign_keys(true)
        .journal_mode(SqliteJournalMode::Wal)
        .busy_timeout(Duration::from_secs(5));
    let db = SqlitePoolOptions::new()
        .max_connections(8)
        .connect_with(options)
        .await
        .context("Cannot open database")?;
    let migrator = sqlx::migrate!("./migrations");
    migrator.run(&db).await.context("Migration failed")?;
    tracing::info!("Database schema up to date (migration {})", migrator.iter().map(|m| m.version).max().unwrap_or(0));
    birthdays::sync_all(&db).await.map_err(|e| anyhow::anyhow!("{e:?}")).context("Could not sync birthday calendars")?;

    let push = push::WebPush::load_or_create(&config.data_dir, config.vapid_subject.clone())?;
    let settings = settings::SettingsStore::load(&db, &config).await.context("Cannot read settings")?;
    let state = AppState {
        settings: Arc::new(settings),
        started_at: chrono::Utc::now(),
        logs,
        db,
        push: Arc::new(push),
        jwt: Arc::new(auth::JwtKeys::new(&config.jwt_secret)),
        config: Arc::new(config.clone()),
        dav_auth: Arc::default(),
    };

    reminders::spawn(state.clone());

    let cors = if config.cors_origins.iter().any(|o| o == "*") {
        CorsLayer::new().allow_origin(Any).allow_methods(Any).allow_headers(Any)
    } else {
        let origins: Vec<HeaderValue> = config.cors_origins.iter().filter_map(|o| o.parse().ok()).collect();
        CorsLayer::new().allow_origin(origins).allow_methods(Any).allow_headers(Any)
    };

    // Single-page app: unknown paths serve index.html.
    let index = config.static_dir.join("index.html");
    let spa = ServeDir::new(&config.static_dir).fallback(ServeFile::new(index));

    let app = Router::new()
        .nest("/api", routes::api_router().layer(cors))
        // CardDAV (DAVx⁵ and others)
        .route("/.well-known/carddav", any(dav::well_known))
        .route("/dav", any(dav::handle))
        .route("/dav/", any(dav::handle))
        .route("/dav/{*path}", any(dav::handle))
        // Some clients first query the start page via PROPFIND
        .route("/", get_service(spa.clone()).fallback(dav::handle))
        .fallback_service(spa)
        .layer(axum::middleware::from_fn(cache_headers))
        .layer(axum::middleware::from_fn(i18n::detect))
        .layer(DefaultBodyLimit::max(10 * 1024 * 1024))
        .layer(TraceLayer::new_for_http())
        .with_state(state);

    let listener = tokio::net::TcpListener::bind(config.bind).await?;
    tracing::info!("Synchaven listening on http://{}", config.bind);
    axum::serve(listener, app).with_graceful_shutdown(shutdown_signal()).await?;
    tracing::info!("Synchaven stopped");
    Ok(())
}

/// Hashed build files may be cached permanently, everything else (especially index.html)
/// must be revalidated – otherwise browsers see the old version after an update.
async fn cache_headers(req: axum::extract::Request, next: axum::middleware::Next) -> axum::response::Response {
    use axum::http::header::CACHE_CONTROL;
    let path = req.uri().path().to_owned();
    let mut res = next.run(req).await;
    if !res.headers().contains_key(CACHE_CONTROL) {
        let value = if path.starts_with("/assets/") {
            "public, max-age=31536000, immutable"
        } else {
            "no-cache"
        };
        res.headers_mut().insert(CACHE_CONTROL, HeaderValue::from_static(value));
    }
    res
}

async fn shutdown_signal() {
    let ctrl_c = async {
        let _ = tokio::signal::ctrl_c().await;
    };
    #[cfg(unix)]
    let terminate = async {
        if let Ok(mut s) = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()) {
            s.recv().await;
        }
    };
    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();
    tokio::select! { _ = ctrl_c => {}, _ = terminate => {} }
    tracing::info!("Shutdown signal received, finishing open requests");
}

/// `synchaven healthcheck` – for the Docker HEALTHCHECK (no curl in the image).
async fn healthcheck() -> anyhow::Result<()> {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let bind: std::net::SocketAddr = std::env::var("BIND_ADDR")
        .unwrap_or_else(|_| "0.0.0.0:8080".into())
        .parse()?;
    let addr = std::net::SocketAddr::from(([127, 0, 0, 1], bind.port()));
    let mut stream = tokio::time::timeout(Duration::from_secs(3), tokio::net::TcpStream::connect(addr)).await??;
    stream.write_all(b"GET /api/info HTTP/1.0\r\nHost: localhost\r\n\r\n").await?;
    let mut buf = vec![0u8; 64];
    let n = tokio::time::timeout(Duration::from_secs(3), stream.read(&mut buf)).await??;
    let head = String::from_utf8_lossy(&buf[..n]);
    anyhow::ensure!(head.contains(" 200 "), "Unexpected response: {head}");
    Ok(())
}
