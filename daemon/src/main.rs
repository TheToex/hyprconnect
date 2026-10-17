mod config;
mod input;
mod ipc;
mod server;
mod system;
mod tls;

use anyhow::{bail, Result};
use serde_json::{json, Value};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::UnixStream;

const HELP: &str = "hyprconnect — phone <-> Hyprland bridge

USAGE:
  hyprconnect daemon              run the daemon (put in Hyprland exec-once)
  hyprconnect tray                run the system tray applet standalone
  hyprconnect pair                show a QR code to pair the phone
  hyprconnect devices             list paired phones, battery, connection state
  hyprconnect send <file>         send a file to the phone
  hyprconnect ring                make the phone ring (find my phone)
  hyprconnect sms <number> <text> send an SMS through the phone
  hyprconnect clip                push desktop clipboard to the phone
  hyprconnect unpair <device-id>  forget a phone
";

async fn ipc(req: Value) -> Result<Value> {
    let s = UnixStream::connect(config::runtime_sock())
        .await
        .map_err(|_| anyhow::anyhow!("daemon not running (start: hyprconnect daemon)"))?;
    let (r, mut w) = s.into_split();
    w.write_all(format!("{req}\n").as_bytes()).await?;
    let mut line = String::new();
    BufReader::new(r).read_line(&mut line).await?;
    let v: Value = serde_json::from_str(&line)?;
    if v["ok"] == json!(false) {
        bail!("{}", v["error"].as_str().unwrap_or("failed"));
    }
    Ok(v)
}

fn print_qr(data: &str) -> Result<()> {
    let code = qrcode::QrCode::new(data.as_bytes())?;
    let s = code
        .render::<qrcode::render::unicode::Dense1x2>()
        .quiet_zone(true)
        .build();
    println!("{s}");
    Ok(())
}

async fn daemon() -> Result<()> {
    let cfg = config::load_config();
    let ident = tls::load_or_create()?;
    let fp = ident.fingerprint.clone();
    let port = cfg.port;
    let st = server::State::new(cfg, ident);

    // mDNS advertisement so the phone can find us after IP changes
    let _mdns = (|| -> Result<mdns_sd::ServiceDaemon> {
        let d = mdns_sd::ServiceDaemon::new()?;
        let host = std::fs::read_to_string("/etc/hostname").unwrap_or_else(|_| "hyprconnect".into());
        let host = host.trim().to_string();
        let props = [("fp", &fp[..16])];
        let info = mdns_sd::ServiceInfo::new(
            "_hyprconnect._tcp.local.",
            &host,
            &format!("{host}.local."),
            "",
            port,
            &props[..],
        )?
        .enable_addr_auto();
        d.register(info)?;
        Ok(d)
    })()
    .map_err(|e| eprintln!("mdns disabled: {e}"))
    .ok();

    tokio::spawn(server::hypr_events(st.clone()));
    let st2 = st.clone();
    tokio::spawn(async move {
        if let Err(e) = ipc::serve(st2).await {
            eprintln!("ipc: {e}");
        }
    });

    // Auto-launch system tray in background
    tokio::spawn(async {
        tokio::time::sleep(tokio::time::Duration::from_millis(500)).await;
        if let Err(e) = system::run_tray(false).await {
            eprintln!("tray: {e}");
        }
    });

    server::run(st).await
}

#[tokio::main]
async fn main() -> Result<()> {
    let a: Vec<String> = std::env::args().skip(1).collect();
    match a.first().map(|s| s.as_str()) {
        Some("daemon") => daemon().await?,
        Some("tray") => system::run_tray(true).await?,
        Some("pair") => {
            let v = ipc(json!({"cmd":"pair"})).await?;
            let uri = v["uri"].as_str().unwrap_or_default();
            print_qr(uri)?;
            println!("Scan with the HyprConnect app (valid 2 min)\n{uri}");
        }
        Some("devices") => {
            let v = ipc(json!({"cmd":"devices"})).await?;
            for d in v["devices"].as_array().unwrap_or(&vec![]) {
                let bat = d["battery"]["pct"].as_i64().map(|p| format!("  battery {p}%")).unwrap_or_default();
                println!("{}  {}  [{}]{}", d["id"].as_str().unwrap_or(""), d["name"].as_str().unwrap_or(""),
                    if d["connected"] == json!(true) { "connected" } else { "offline" }, bat);
            }
        }
        Some("send") if a.len() >= 2 => {
            let p = std::fs::canonicalize(&a[1])?;
            let v = ipc(json!({"cmd":"send","path":p})).await?;
            println!("sending {}", v["name"].as_str().unwrap_or(""));
        }
        Some("ring") => { ipc(json!({"cmd":"ring"})).await?; }
        Some("sms") if a.len() >= 3 => { ipc(json!({"cmd":"sms","to":a[1],"body":a[2..].join(" ")})).await?; }
        Some("clip") => { ipc(json!({"cmd":"clip"})).await?; }
        Some("unpair") if a.len() >= 2 => { ipc(json!({"cmd":"unpair","id":a[1]})).await?; }
        _ => print!("{HELP}"),
    }
    Ok(())
}
