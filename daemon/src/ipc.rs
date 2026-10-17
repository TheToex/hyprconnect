//! Local control socket so the CLI can talk to the running daemon.
use anyhow::Result;
use serde_json::{json, Value};
use std::sync::Arc;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::UnixListener;

use crate::config::{runtime_sock, save_devices};
use crate::server::{send_file, State};

pub async fn serve(st: Arc<State>) -> Result<()> {
    let p = runtime_sock();
    let _ = std::fs::remove_file(&p);
    let l = UnixListener::bind(&p)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&p, std::fs::Permissions::from_mode(0o600))?;
    }
    loop {
        let (s, _) = l.accept().await?;
        let st = st.clone();
        tokio::spawn(async move {
            let (r, mut w) = s.into_split();
            let mut line = String::new();
            if BufReader::new(r).read_line(&mut line).await.is_err() {
                return;
            }
            let resp = match serde_json::from_str::<Value>(&line) {
                Ok(req) => handle(&st, req).await.unwrap_or_else(|e| json!({"ok":false,"error":e.to_string()})),
                Err(e) => json!({"ok":false,"error":e.to_string()}),
            };
            let _ = w.write_all(format!("{resp}\n").as_bytes()).await;
        });
    }
}

async fn handle(st: &Arc<State>, req: Value) -> Result<Value> {
    let dev = req["device"].as_str();
    let need = |tx: Option<tokio::sync::mpsc::Sender<Value>>| tx.ok_or_else(|| anyhow::anyhow!("no phone connected"));
    Ok(match req["cmd"].as_str().unwrap_or("") {
        "pair" => {
            let token = st.start_pairing();
            let ip = local_ip_address::local_ip()?;
            let host = std::fs::read_to_string("/etc/hostname").unwrap_or_default().trim().to_string();
            let uri = format!("hyprconnect://{ip}:{}?fp={}&token={token}&name={host}", st.cfg.port, st.ident.fingerprint);
            json!({"ok":true,"uri":uri,"expires_in":120})
        }
        "devices" => {
            let paired = st.devices.lock().unwrap().paired.clone();
            let sessions = st.sessions.lock().unwrap();
            let list: Vec<Value> = paired.iter().map(|(id, d)| {
                let s = sessions.get(id);
                json!({"id":id,"name":d.name,"connected":s.is_some(),
                       "battery": s.and_then(|s| s.battery).map(|(p,c)| json!({"pct":p,"charging":c}))})
            }).collect();
            json!({"ok":true,"devices":list})
        }
        "send" => {
            let path = req["path"].as_str().ok_or_else(|| anyhow::anyhow!("path missing"))?;
            let tx = need(st.session_tx(dev))?;
            let name = send_file(tx, path.into()).await?;
            json!({"ok":true,"name":name})
        }
        "ring" => { need(st.session_tx(dev))?.send(json!({"t":"ring"})).await?; json!({"ok":true}) }
        "sms" => {
            let (to, body) = (req["to"].as_str().unwrap_or(""), req["body"].as_str().unwrap_or(""));
            need(st.session_tx(dev))?.send(json!({"t":"sms.send","to":to,"body":body})).await?;
            json!({"ok":true})
        }
        "clip" => {
            let text = crate::system::clip_get().await;
            need(st.session_tx(dev))?.send(json!({"t":"clip","text":text})).await?;
            json!({"ok":true})
        }
        "unpair" => {
            let id = req["id"].as_str().unwrap_or("");
            let mut d = st.devices.lock().unwrap();
            let had = d.paired.remove(id).is_some();
            save_devices(&d)?;
            st.sessions.lock().unwrap().remove(id);
            json!({"ok":had})
        }
        _ => json!({"ok":false,"error":"unknown command"}),
    })
}
