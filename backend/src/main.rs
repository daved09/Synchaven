mod auth;
mod config;
mod contacts;
mod dav;
mod error;
mod ical;
mod models;
mod recurrence;
mod routes;
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

#[derive(Clone)]
pub struct AppState {
    pub db: SqlitePool,
    pub config: Arc<Config>,
    pub jwt: Arc<auth::JwtKeys>,
    pub dav_auth: Arc<dav::AuthCache>,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    if std::env::args().nth(1).as_deref() == Some("healthcheck") {
        return healthcheck().await;
    }
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "synchaven=info,tower_http=warn".into()),
        )
        .init();

    let config = Config::from_env()?;

    let options = SqliteConnectOptions::from_str(&format!("sqlite://{}", config.db_path().display()))?
        .create_if_missing(true)
        .foreign_keys(true)
        .journal_mode(SqliteJournalMode::Wal)
        .busy_timeout(Duration::from_secs(5));
    let db = SqlitePoolOptions::new()
        .max_connections(8)
        .connect_with(options)
        .await
        .context("Datenbank kann nicht geöffnet werden")?;
    sqlx::migrate!("./migrations").run(&db).await.context("Migration fehlgeschlagen")?;

    let state = AppState {
        db,
        jwt: Arc::new(auth::JwtKeys::new(&config.jwt_secret, config.token_ttl_days)),
        config: Arc::new(config.clone()),
        dav_auth: Arc::default(),
    };

    let cors = if config.cors_origins.iter().any(|o| o == "*") {
        CorsLayer::new().allow_origin(Any).allow_methods(Any).allow_headers(Any)
    } else {
        let origins: Vec<HeaderValue> = config.cors_origins.iter().filter_map(|o| o.parse().ok()).collect();
        CorsLayer::new().allow_origin(origins).allow_methods(Any).allow_headers(Any)
    };

    // Single-Page-App: unbekannte Pfade liefern index.html aus.
    let index = config.static_dir.join("index.html");
    let spa = ServeDir::new(&config.static_dir).fallback(ServeFile::new(index));

    let app = Router::new()
        .nest("/api", routes::api_router().layer(cors))
        // CardDAV (DAVx⁵ u. a.)
        .route("/.well-known/carddav", any(dav::well_known))
        .route("/dav", any(dav::handle))
        .route("/dav/", any(dav::handle))
        .route("/dav/{*path}", any(dav::handle))
        // Manche Clients fragen zuerst die Startseite per PROPFIND an
        .route("/", get_service(spa.clone()).fallback(dav::handle))
        .fallback_service(spa)
        .layer(axum::middleware::from_fn(cache_headers))
        .layer(DefaultBodyLimit::max(10 * 1024 * 1024))
        .layer(TraceLayer::new_for_http())
        .with_state(state);

    let listener = tokio::net::TcpListener::bind(config.bind).await?;
    tracing::info!("Synchaven läuft auf http://{}", config.bind);
    axum::serve(listener, app).with_graceful_shutdown(shutdown_signal()).await?;
    Ok(())
}

/// Gehashte Build-Dateien dürfen dauerhaft gecacht werden, alles andere (v. a. index.html)
/// muss neu geprüft werden – sonst sehen Browser nach einem Update die alte Version.
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
}

/// `synchaven healthcheck` – für den Docker-HEALTHCHECK (ohne curl im Image).
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
    anyhow::ensure!(head.contains(" 200 "), "Unerwartete Antwort: {head}");
    Ok(())
}
