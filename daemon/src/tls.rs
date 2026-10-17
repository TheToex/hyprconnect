use anyhow::{Context, Result};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use sha2::{Digest, Sha256};
use std::sync::Arc;
use tokio_rustls::TlsAcceptor;

use crate::config::config_dir;

pub struct Identity {
    pub acceptor: TlsAcceptor,
    /// SHA-256 fingerprint of the DER certificate, lowercase hex (phone pins this).
    pub fingerprint: String,
}

pub fn load_or_create() -> Result<Identity> {
    let dir = config_dir();
    std::fs::create_dir_all(&dir)?;
    let cert_p = dir.join("cert.der");
    let key_p = dir.join("key.der");

    if !cert_p.exists() || !key_p.exists() {
        let host = std::fs::read_to_string("/etc/hostname")
            .unwrap_or_else(|_| "hyprconnect".into())
            .trim()
            .to_string();
        let ck = rcgen::generate_simple_self_signed(vec![host, "hyprconnect.local".into()])?;
        std::fs::write(&cert_p, ck.cert.der())?;
        std::fs::write(&key_p, ck.key_pair.serialize_der())?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&key_p, std::fs::Permissions::from_mode(0o600))?;
        }
    }

    let cert = std::fs::read(&cert_p).context("read cert")?;
    let key = std::fs::read(&key_p).context("read key")?;
    let fingerprint = hex::encode(Sha256::digest(&cert));

    let cfg = rustls::ServerConfig::builder_with_provider(Arc::new(
        rustls::crypto::ring::default_provider(),
    ))
    .with_safe_default_protocol_versions()?
    .with_no_client_auth()
    .with_single_cert(
        vec![CertificateDer::from(cert)],
        PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(key)),
    )?;

    Ok(Identity {
        acceptor: TlsAcceptor::from(Arc::new(cfg)),
        fingerprint,
    })
}
