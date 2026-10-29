use hbb_common::{
    async_recursion::async_recursion,
    config::{Config, Socks5Server},
    log::{self, info},
    proxy::{Proxy, ProxyScheme},
    tls::{
        get_cached_tls_accept_invalid_cert, get_cached_tls_type, is_plain, upsert_tls_cache,
        TlsType,
    },
};
use std::{
    collections::HashMap,
    sync::Mutex,
    time::Duration,
};

use reqwest::{blocking::Client as SyncClient, Client as AsyncClient};

lazy_static::lazy_static! {
    // 复用 AsyncClient（reqwest Client 内部带连接池，设计上就应全局共享）。
    // 心跳每 15s 一次，复用后同一台设备只保持 1 条到 API 服务的 keep-alive 连接，
    // 不再每次新建 TCP + 完整 TLS 握手，可大幅降低服务端的连接 churn / TIME-WAIT / 签名 CPU。
    // key = (TLS 类型, 是否信任非法证书, 代理配置摘要)。
    static ref ASYNC_CLIENT_CACHE: Mutex<HashMap<(u8, bool, Option<String>), AsyncClient>> =
        Mutex::new(HashMap::new());
}

#[inline]
fn tls_type_key(t: TlsType) -> u8 {
    match t {
        TlsType::Plain => 0,
        TlsType::NativeTls => 1,
        TlsType::Rustls => 2,
    }
}

#[inline]
fn socks_key() -> Option<String> {
    Config::get_socks().map(|c| format!("{}|{}|{}", c.proxy, c.username, c.password))
}

/// 取可复用的异步 HTTP Client（内置连接池，HTTP keep-alive）。
/// 心跳/sysinfo 等高频请求都应走这里，而不是每次 new 一个 Client。
pub fn get_cached_http_client_async(
    tls_type: TlsType,
    danger_accept_invalid_cert: bool,
) -> AsyncClient {
    let key = (
        tls_type_key(tls_type),
        danger_accept_invalid_cert,
        socks_key(),
    );
    let mut cache = ASYNC_CLIENT_CACHE.lock().unwrap();
    if let Some(client) = cache.get(&key) {
        // Client 是 Arc 句柄，clone 廉价且共享同一连接池。
        return client.clone();
    }
    let client = create_http_client_async(tls_type, danger_accept_invalid_cert);
    cache.insert(key, client.clone());
    client
}

macro_rules! configure_http_client {
    ($builder:expr, $tls_type:expr, $danger_accept_invalid_cert:expr, $Client: ty) => {{
        // https://github.com/rustdesk/rustdesk/issues/11569
        // https://docs.rs/reqwest/latest/reqwest/struct.ClientBuilder.html#method.no_proxy
        // 空闲连接保留 60s（短于 nginx 默认 keepalive_timeout 75s，由客户端先回收，
        // 避免复用到服务端已关闭的连接）；TCP keepalive 30s 探测移动 NAT 断连。
        let mut builder = $builder
            .no_proxy()
            .pool_idle_timeout(Duration::from_secs(60))
            .tcp_keepalive(Duration::from_secs(30));

        match $tls_type {
            TlsType::Plain => {}
            TlsType::NativeTls => {
                builder = builder.use_native_tls();
                if $danger_accept_invalid_cert {
                    builder = builder.danger_accept_invalid_certs(true);
                }
            }
            TlsType::Rustls => {
                #[cfg(any(target_os = "android", target_os = "ios"))]
                match hbb_common::verifier::client_config($danger_accept_invalid_cert) {
                    Ok(client_config) => {
                        builder = builder.use_preconfigured_tls(client_config);
                    }
                    Err(e) => {
                        hbb_common::log::error!("Failed to get client config: {}", e);
                    }
                }
                #[cfg(not(any(target_os = "android", target_os = "ios")))]
                {
                    builder = builder.use_rustls_tls();
                    if $danger_accept_invalid_cert {
                        builder = builder.danger_accept_invalid_certs(true);
                    }
                }
            }
        }

        let client = if let Some(conf) = Config::get_socks() {
            let proxy_result = Proxy::from_conf(&conf, None);

            match proxy_result {
                Ok(proxy) => {
                    let proxy_setup = match &proxy.intercept {
                        ProxyScheme::Http { host, .. } => {
                            reqwest::Proxy::all(format!("http://{}", host))
                        }
                        ProxyScheme::Https { host, .. } => {
                            reqwest::Proxy::all(format!("https://{}", host))
                        }
                        ProxyScheme::Socks5 { addr, .. } => {
                            reqwest::Proxy::all(&format!("socks5://{}", addr))
                        }
                    };

                    match proxy_setup {
                        Ok(mut p) => {
                            if let Some(auth) = proxy.intercept.maybe_auth() {
                                if !auth.username().is_empty() && !auth.password().is_empty() {
                                    p = p.basic_auth(auth.username(), auth.password());
                                }
                            }
                            builder = builder.proxy(p);
                            builder.build().unwrap_or_else(|e| {
                                info!("Failed to create a proxied client: {}", e);
                                <$Client>::new()
                            })
                        }
                        Err(e) => {
                            info!("Failed to set up proxy: {}", e);
                            <$Client>::new()
                        }
                    }
                }
                Err(e) => {
                    info!("Failed to configure proxy: {}", e);
                    <$Client>::new()
                }
            }
        } else {
            builder.build().unwrap_or_else(|e| {
                info!("Failed to create a client: {}", e);
                <$Client>::new()
            })
        };

        client
    }};
}

pub fn create_http_client(tls_type: TlsType, danger_accept_invalid_cert: bool) -> SyncClient {
    let builder = SyncClient::builder();
    configure_http_client!(builder, tls_type, danger_accept_invalid_cert, SyncClient)
}

pub fn create_http_client_async(
    tls_type: TlsType,
    danger_accept_invalid_cert: bool,
) -> AsyncClient {
    let builder = AsyncClient::builder();
    configure_http_client!(builder, tls_type, danger_accept_invalid_cert, AsyncClient)
}

pub fn get_url_for_tls<'a>(url: &'a str, proxy_conf: &'a Option<Socks5Server>) -> &'a str {
    if is_plain(url) {
        if let Some(conf) = proxy_conf {
            if conf.proxy.starts_with("https://") {
                return &conf.proxy;
            }
        }
    }
    url
}

pub fn create_http_client_with_url(url: &str) -> SyncClient {
    let proxy_conf = Config::get_socks();
    let tls_url = get_url_for_tls(url, &proxy_conf);
    let tls_type = get_cached_tls_type(tls_url);
    let is_tls_type_cached = tls_type.is_some();
    let tls_type = tls_type.unwrap_or(TlsType::Rustls);
    let tls_danger_accept_invalid_cert = get_cached_tls_accept_invalid_cert(tls_url);
    create_http_client_with_url_(
        url,
        tls_url,
        tls_type,
        is_tls_type_cached,
        tls_danger_accept_invalid_cert,
        tls_danger_accept_invalid_cert,
    )
}

fn create_http_client_with_url_(
    url: &str,
    tls_url: &str,
    tls_type: TlsType,
    is_tls_type_cached: bool,
    danger_accept_invalid_cert: Option<bool>,
    original_danger_accept_invalid_cert: Option<bool>,
) -> SyncClient {
    let mut client = create_http_client(tls_type, danger_accept_invalid_cert.unwrap_or(false));
    if is_tls_type_cached && original_danger_accept_invalid_cert.is_some() {
        return client;
    }
    if let Err(e) = client.head(url).send() {
        if e.is_request() {
            match (tls_type, is_tls_type_cached, danger_accept_invalid_cert) {
                (TlsType::Rustls, _, None) => {
                    log::warn!(
                        "Failed to connect to server {} with rustls-tls: {:?}, trying accept invalid cert",
                        tls_url,
                        e
                    );
                    client = create_http_client_with_url_(
                        url,
                        tls_url,
                        tls_type,
                        is_tls_type_cached,
                        Some(true),
                        original_danger_accept_invalid_cert,
                    );
                }
                (TlsType::Rustls, false, Some(_)) => {
                    log::warn!(
                        "Failed to connect to server {} with rustls-tls: {:?}, trying native-tls",
                        tls_url,
                        e
                    );
                    client = create_http_client_with_url_(
                        url,
                        tls_url,
                        TlsType::NativeTls,
                        is_tls_type_cached,
                        original_danger_accept_invalid_cert,
                        original_danger_accept_invalid_cert,
                    );
                }
                (TlsType::NativeTls, _, None) => {
                    log::warn!(
                        "Failed to connect to server {} with native-tls: {:?}, trying accept invalid cert",
                        tls_url,
                        e
                    );
                    client = create_http_client_with_url_(
                        url,
                        tls_url,
                        tls_type,
                        is_tls_type_cached,
                        Some(true),
                        original_danger_accept_invalid_cert,
                    );
                }
                _ => {
                    log::error!(
                        "Failed to connect to server {} with {:?}, err: {:?}.",
                        tls_url,
                        tls_type,
                        e
                    );
                }
            }
        } else {
            log::warn!(
                "Failed to connect to server {} with {:?}, err: {}.",
                tls_url,
                tls_type,
                e
            );
        }
    } else {
        log::info!(
            "Successfully connected to server {} with {:?}",
            tls_url,
            tls_type
        );
        upsert_tls_cache(
            tls_url,
            tls_type,
            danger_accept_invalid_cert.unwrap_or(false),
        );
    }
    client
}

pub async fn create_http_client_async_with_url(url: &str) -> AsyncClient {
    let proxy_conf = Config::get_socks();
    let tls_url = get_url_for_tls(url, &proxy_conf);
    let tls_type = get_cached_tls_type(tls_url);
    let is_tls_type_cached = tls_type.is_some();
    let tls_type = tls_type.unwrap_or(TlsType::Rustls);
    let danger_accept_invalid_cert = get_cached_tls_accept_invalid_cert(tls_url);
    create_http_client_async_with_url_(
        url,
        tls_url,
        tls_type,
        is_tls_type_cached,
        danger_accept_invalid_cert,
        danger_accept_invalid_cert,
    )
    .await
}

#[async_recursion]
async fn create_http_client_async_with_url_(
    url: &str,
    tls_url: &str,
    tls_type: TlsType,
    is_tls_type_cached: bool,
    danger_accept_invalid_cert: Option<bool>,
    original_danger_accept_invalid_cert: Option<bool>,
) -> AsyncClient {
    let mut client =
        get_cached_http_client_async(tls_type, danger_accept_invalid_cert.unwrap_or(false));
    if is_tls_type_cached && original_danger_accept_invalid_cert.is_some() {
        return client;
    }
    if let Err(e) = client.head(url).send().await {
        match (tls_type, is_tls_type_cached, danger_accept_invalid_cert) {
            (TlsType::Rustls, _, None) => {
                log::warn!(
                    "Failed to connect to server {} with rustls-tls: {:?}, trying accept invalid cert",
                    tls_url,
                    e
                );
                client = create_http_client_async_with_url_(
                    url,
                    tls_url,
                    tls_type,
                    is_tls_type_cached,
                    Some(true),
                    original_danger_accept_invalid_cert,
                )
                .await;
            }
            (TlsType::Rustls, false, Some(_)) => {
                log::warn!(
                    "Failed to connect to server {} with rustls-tls: {:?}, trying native-tls",
                    tls_url,
                    e
                );
                client = create_http_client_async_with_url_(
                    url,
                    tls_url,
                    TlsType::NativeTls,
                    is_tls_type_cached,
                    original_danger_accept_invalid_cert,
                    original_danger_accept_invalid_cert,
                )
                .await;
            }
            (TlsType::NativeTls, _, None) => {
                log::warn!(
                    "Failed to connect to server {} with native-tls: {:?}, trying accept invalid cert",
                    tls_url,
                    e
                );
                client = create_http_client_async_with_url_(
                    url,
                    tls_url,
                    tls_type,
                    is_tls_type_cached,
                    Some(true),
                    original_danger_accept_invalid_cert,
                )
                .await;
            }
            _ => {
                log::error!(
                    "Failed to connect to server {} with {:?}, err: {:?}.",
                    tls_url,
                    tls_type,
                    e
                );
            }
        }
    } else {
        log::info!(
            "Successfully connected to server {} with {:?}",
            tls_url,
            tls_type
        );
        upsert_tls_cache(
            tls_url,
            tls_type,
            danger_accept_invalid_cert.unwrap_or(false),
        );
    }
    client
}
