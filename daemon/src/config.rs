use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::path::PathBuf;

pub const DEFAULT_PORT: u16 = 5716;

#[derive(Serialize, Deserialize, Clone)]
pub struct Config {
    pub port: u16,
    pub download_dir: String,
    /// Commands the phone may trigger by NAME only (phone never sends raw shell).
    pub commands: BTreeMap<String, String>,
}

impl Default for Config {
    fn default() -> Self {
        let mut commands = BTreeMap::new();
        commands.insert("lock".into(), "hyprlock".into());
        commands.insert("screenshot".into(), "sh -c 'grim - | wl-copy'".into());
        commands.insert("suspend".into(), "systemctl suspend".into());
        Config {
            port: DEFAULT_PORT,
            download_dir: home().join("Downloads/hyprconnect").to_string_lossy().into(),
            commands,
        }
    }
}

#[derive(Serialize, Deserialize, Default, Clone)]
pub struct Devices {
    /// device id -> (name, sha256 hex of secret)
    pub paired: BTreeMap<String, Device>,
}

#[derive(Serialize, Deserialize, Clone)]
pub struct Device {
    pub name: String,
    pub secret_hash: String,
}

pub fn home() -> PathBuf {
    PathBuf::from(std::env::var("HOME").unwrap_or_else(|_| "/tmp".into()))
}

pub fn config_dir() -> PathBuf {
    std::env::var("XDG_CONFIG_HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|_| home().join(".config"))
        .join("hyprconnect")
}

pub fn runtime_sock() -> PathBuf {
    std::env::var("XDG_RUNTIME_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from("/tmp"))
        .join("hyprconnect.sock")
}

pub fn load_config() -> Config {
    let p = config_dir().join("config.json");
    if let Ok(s) = std::fs::read_to_string(&p) {
        if let Ok(c) = serde_json::from_str(&s) {
            return c;
        }
        eprintln!("warning: could not parse {}, using defaults", p.display());
        return Config::default();
    }
    let c = Config::default();
    let _ = std::fs::create_dir_all(config_dir());
    let _ = std::fs::write(&p, serde_json::to_string_pretty(&c).unwrap());
    c
}

pub fn load_devices() -> Devices {
    std::fs::read_to_string(config_dir().join("devices.json"))
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default()
}

pub fn save_devices(d: &Devices) -> anyhow::Result<()> {
    std::fs::create_dir_all(config_dir())?;
    let p = config_dir().join("devices.json");
    std::fs::write(&p, serde_json::to_string_pretty(d)?)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&p, std::fs::Permissions::from_mode(0o600))?;
    }
    Ok(())
}
