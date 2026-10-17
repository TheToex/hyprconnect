//! Thin wrappers around the tools a Hyprland desktop already has.
use serde_json::{json, Value};
use std::process::Stdio;
use tokio::io::AsyncWriteExt;
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

pub async fn type_text(text: &str) {
    // wtype handles unicode (Persian included)
    let t: String = text.chars().take(2000).collect();
    let _ = Command::new("wtype")
        .arg("--")
        .arg(t)
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .await;
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
    spawn("hyprctl", &["dispatch", "workspace", &id.to_string()]);
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
