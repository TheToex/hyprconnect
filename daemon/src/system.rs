//! Thin wrappers around the tools a Hyprland desktop already has.
use anyhow::{bail, Result};
use serde_json::{json, Value};
use std::process::Stdio;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt};
use tokio::process::Command;

pub fn spawn(prog: &str, args: &[&str]) {
    let mut c = Command::new(prog);
    c.args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    match c.spawn() {
        Ok(mut ch) => {
            tokio::spawn(async move {
                let _ = ch.wait().await;
            });
        }
        Err(e) => eprintln!("spawn {prog}: {e}"),
    }
}

pub async fn output(prog: &str, args: &[&str]) -> Option<String> {
    let out = Command::new(prog)
        .args(args)
        .stdin(Stdio::null())
        .stderr(Stdio::null())
        .output()
        .await
        .ok()?;
    if out.status.success() {
        Some(String::from_utf8_lossy(&out.stdout).into_owned())
    } else {
        None
    }
}

pub async fn pipe_to(prog: &str, args: &[&str], data: &str) {
    let Ok(mut ch) = Command::new(prog)
        .args(args)
        .stdin(Stdio::piped())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
    else {
        return;
    };
    if let Some(mut si) = ch.stdin.take() {
        let _ = si.write_all(data.as_bytes()).await;
    }
    let _ = ch.wait().await;
}

pub fn notify(app: &str, title: &str, body: &str) {
    spawn("notify-send", &["-a", app, "--", title, body]);
}

pub fn media(action: &str) {
    let a = match action {
        "play_pause" => "play-pause",
        "next" => "next",
        "prev" => "previous",
        "stop" => "stop",
        _ => return,
    };
    spawn("playerctl", &[a]);
}

pub fn volume(action: &str) {
    match action {
        "up" => spawn("wpctl", &["set-volume", "-l", "1.0", "@DEFAULT_AUDIO_SINK@", "5%+"]),
        "down" => spawn("wpctl", &["set-volume", "@DEFAULT_AUDIO_SINK@", "5%-"]),
        "mute" => spawn("wpctl", &["set-mute", "@DEFAULT_AUDIO_SINK@", "toggle"]),
        _ => {}
    }
}

pub fn brightness(action: &str) {
    match action {
        "up" => spawn("brightnessctl", &["set", "5%+"]),
        "down" => spawn("brightnessctl", &["set", "5%-"]),
        _ => {}
    }
}

pub async fn try_wtype(text: &str) -> bool {
    // wtype handles unicode (Persian included) if installed
    let t: String = text.chars().take(2000).collect();
    match Command::new("wtype")
        .arg("--")
        .arg(t)
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .await
    {
        Ok(s) => s.success(),
        Err(_) => false,
    }
}

pub async fn clip_get() -> String {
    output("wl-paste", &["--no-newline"]).await.unwrap_or_default()
}

pub async fn clip_set(text: &str) {
    pipe_to("wl-copy", &[], text).await;
}

pub async fn ws_state() -> Value {
    let ws: Value = output("hyprctl", &["-j", "workspaces"])
        .await
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or(json!([]));
    let active: Value = output("hyprctl", &["-j", "activeworkspace"])
        .await
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or(json!({}));
    let mut list: Vec<Value> = ws
        .as_array()
        .cloned()
        .unwrap_or_default()
        .iter()
        .map(|w| json!({"id": w["id"], "name": w["name"], "windows": w["windows"]}))
        .collect();
    list.sort_by_key(|w| w["id"].as_i64().unwrap_or(0));
    json!({"t":"ws.state","active":active["id"],"workspaces":list})
}

pub async fn win_list() -> Value {
    let cl: Value = output("hyprctl", &["-j", "clients"])
        .await
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or(json!([]));
    let wins: Vec<Value> = cl
        .as_array()
        .cloned()
        .unwrap_or_default()
        .iter()
        .filter(|c| c["mapped"].as_bool().unwrap_or(true))
        .map(|c| json!({
            "address": c["address"], "title": c["title"],
            "class": c["class"], "workspace": c["workspace"]["id"]
        }))
        .collect();
    json!({"t":"win.list","windows":wins})
}

pub fn ws_switch(id: i64) {
    tokio::spawn(async move {
        let res = output("hyprctl", &["dispatch", &format!("hl.dsp.focus({{workspace = {id}}})")]).await;
        if res.as_deref() != Some("ok\n") && res.as_deref() != Some("ok") {
            spawn("hyprctl", &["dispatch", "workspace", &id.to_string()]);
        }
    });
}

pub fn brightness_set(pct: i32) {
    let pct = pct.clamp(1, 100);
    spawn("brightnessctl", &["set", &format!("{pct}%")]);
}

pub async fn get_brightness() -> Option<i32> {
    if let Ok(out) = Command::new("brightnessctl").arg("-m").output().await {
        if out.status.success() {
            let s = String::from_utf8_lossy(&out.stdout);
            // format: intel_backlight,backlight,21333,100%,21333
            if let Some(pct_str) = s.lines().next().and_then(|l| l.split(',').nth(3)) {
                let clean = pct_str.trim().trim_end_matches('%');
                if let Ok(val) = clean.parse::<i32>() {
                    return Some(val);
                }
            }
        }
    }
    None
}

pub async fn capture_screen() -> Option<String> {
    use base64::{engine::general_purpose::STANDARD as B64, Engine};
    let out = Command::new("grim")
        .args(["-t", "jpeg", "-q", "40", "-"])
        .stdin(Stdio::null())
        .stderr(Stdio::null())
        .output()
        .await
        .ok()?;
    if out.status.success() && !out.stdout.is_empty() {
        Some(B64.encode(&out.stdout))
    } else {
        None
    }
}

static LASER: tokio::sync::Mutex<Option<tokio::process::ChildStdin>> = tokio::sync::Mutex::const_new(None);
const LASER_SCRIPT: &str = include_str!("../../bin/hyprconnect-laser");

pub async fn laser_start() {
    let mut guard = LASER.lock().await;
    if guard.is_none() {
        let script_path = std::env::var("XDG_RUNTIME_DIR")
            .map(|r| format!("{r}/hyprconnect-laser"))
            .unwrap_or_else(|_| "/tmp/hyprconnect-laser".into());
        let _ = tokio::fs::write(&script_path, LASER_SCRIPT).await;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let _ = tokio::fs::set_permissions(&script_path, std::fs::Permissions::from_mode(0o755)).await;
        }
        let ch = Command::new("python3")
            .arg("-u")
            .arg(&script_path)
            .stdin(Stdio::piped())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn();
        if let Ok(mut c) = ch {
            *guard = c.stdin.take();
        }
    }
    if let Some(stdin) = guard.as_mut() {
        let _ = stdin.write_all(b"start\n").await;
        let _ = stdin.flush().await;
    }
}

pub async fn laser_move(dx: i32, dy: i32) {
    let mut guard = LASER.lock().await;
    if let Some(stdin) = guard.as_mut() {
        let cmd = format!("move {dx} {dy}\n");
        if stdin.write_all(cmd.as_bytes()).await.is_err() || stdin.flush().await.is_err() {
            *guard = None;
        }
    }
}

pub async fn laser_stop() {
    let mut guard = LASER.lock().await;
    if let Some(stdin) = guard.as_mut() {
        let _ = stdin.write_all(b"stop\n").await;
        let _ = stdin.flush().await;
    }
}

static MIRROR: tokio::sync::Mutex<Option<tokio::process::ChildStdin>> = tokio::sync::Mutex::const_new(None);
const MIRROR_SCRIPT: &str = include_str!("../../bin/hyprconnect-mirror");

pub async fn mirror_start(title: &str, width: i32, height: i32, tx: tokio::sync::mpsc::Sender<serde_json::Value>) {
    let mut guard = MIRROR.lock().await;
    if let Some(mut old) = guard.take() {
        let _ = old.write_all(b"quit\n").await;
        let _ = old.flush().await;
    }
    let script_path = std::env::var("XDG_RUNTIME_DIR")
        .map(|r| format!("{r}/hyprconnect-mirror"))
        .unwrap_or_else(|_| "/tmp/hyprconnect-mirror".into());
    let _ = tokio::fs::write(&script_path, MIRROR_SCRIPT).await;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = tokio::fs::set_permissions(&script_path, std::fs::Permissions::from_mode(0o755)).await;
    }
    let mut child = match Command::new("python3")
        .arg("-u")
        .arg(&script_path)
        .arg(title)
        .arg(width.to_string())
        .arg(height.to_string())
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
    {
        Ok(c) => c,
        Err(e) => {
            eprintln!("failed to spawn mirror window: {e}");
            return;
        }
    };

    *guard = child.stdin.take();

    // Notify hyprctl to float mirror window if possible
    spawn("hyprctl", &["dispatch", "setfloating", "title:^HyprConnect.*"]);

    tokio::spawn(async move {
        if let Some(stdout) = child.stdout.take() {
            let mut reader = tokio::io::BufReader::new(stdout);
            let mut line = String::new();
            while let Ok(n) = reader.read_line(&mut line).await {
                if n == 0 { break; }
                if line.trim() == "closed" {
                    let _ = tx.send(serde_json::json!({"t":"mirror.stopped"})).await;
                    break;
                }
                line.clear();
            }
        }
        let _ = child.wait().await;
        let _ = tx.send(serde_json::json!({"t":"mirror.stopped"})).await;
    });
}

pub async fn mirror_frame(data: &str) {
    let mut guard = MIRROR.lock().await;
    if let Some(stdin) = guard.as_mut() {
        let cmd = format!("frame {data}\n");
        if stdin.write_all(cmd.as_bytes()).await.is_err() || stdin.flush().await.is_err() {
            *guard = None;
        }
    }
}

pub async fn mirror_stop() {
    let mut guard = MIRROR.lock().await;
    if let Some(mut stdin) = guard.take() {
        let _ = stdin.write_all(b"stop\n").await;
        let _ = stdin.flush().await;
    }
}

pub fn win_focus(address: &str) {
    // only accept 0x… hex addresses
    if address.starts_with("0x") && address[2..].chars().all(|c| c.is_ascii_hexdigit()) {
        spawn("hyprctl", &["dispatch", "focuswindow", &format!("address:{address}")]);
    }
}

pub fn run_shell(cmdline: &str) {
    spawn("sh", &["-c", cmdline]);
}

const TRAY_SCRIPT: &str = include_str!("../../bin/hyprconnect-tray");

pub async fn run_tray(foreground: bool) -> Result<()> {
    let script_path = std::env::var("XDG_RUNTIME_DIR")
        .map(|r| format!("{r}/hyprconnect-tray"))
        .unwrap_or_else(|_| "/tmp/hyprconnect-tray".into());
    let _ = tokio::fs::write(&script_path, TRAY_SCRIPT).await;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = tokio::fs::set_permissions(&script_path, std::fs::Permissions::from_mode(0o755)).await;
    }
    let mut cmd = Command::new("python3");
    cmd.arg("-u").arg(&script_path);
    if foreground {
        let status = cmd.status().await?;
        if !status.success() {
            bail!("tray applet exited with status: {status}");
        }
    } else {
        cmd.stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()?;
    }
    Ok(())
}


