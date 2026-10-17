use anyhow::Result;
use base64::{engine::general_purpose::STANDARD as B64, Engine};
use rand::RngCore;
use serde::Deserialize;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tokio::fs::File;
use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::mpsc;

use crate::config::{self, Config, Device, Devices};
use crate::input::Input;
use crate::system as sys;
use crate::tls::Identity;

const MAX_LINE: u64 = 2 * 1024 * 1024;
const CHUNK: usize = 48 * 1024;
const MAX_FILE: u64 = 8 * 1024 * 1024 * 1024;

pub struct Session {
    #[allow(dead_code)]
    pub name: String,
    pub tx: mpsc::Sender<Value>,
    pub battery: Option<(i64, bool)>,
}

pub struct State {
    pub cfg: Config,
    pub ident: Identity,
    pub devices: Mutex<Devices>,
    pub pairing: Mutex<Option<(String, Instant)>>,
    pub sessions: Mutex<HashMap<String, Session>>,
    pub input: Mutex<Option<Input>>,
}

impl State {
    pub fn new(cfg: Config, ident: Identity) -> Arc<Self> {
        let input = match Input::new() {
            Ok(i) => Some(i),
            Err(e) => {
                eprintln!("warning: input injection disabled: {e}");
                None
            }
        };
        Arc::new(State {
            cfg,
            ident,
            devices: Mutex::new(config::load_devices()),
            pairing: Mutex::new(None),
            sessions: Mutex::new(HashMap::new()),
            input: Mutex::new(input),
        })
    }

    pub fn start_pairing(&self) -> String {
        let mut b = [0u8; 16];
        rand::rngs::OsRng.fill_bytes(&mut b);
        let t = hex::encode(b);
        *self.pairing.lock().unwrap() = Some((t.clone(), Instant::now() + Duration::from_secs(120)));
        t
    }

    pub async fn broadcast(&self, v: Value) {
        let txs: Vec<_> = self.sessions.lock().unwrap().values().map(|s| s.tx.clone()).collect();
        for tx in txs {
            let _ = tx.send(v.clone()).await;
        }
    }

    pub fn session_tx(&self, id: Option<&str>) -> Option<mpsc::Sender<Value>> {
        let s = self.sessions.lock().unwrap();
        match id {
            Some(i) => s.get(i).map(|x| x.tx.clone()),
            None => s.values().next().map(|x| x.tx.clone()),
        }
    }
}

fn sha(s: &str) -> String {
    hex::encode(Sha256::digest(s.as_bytes()))
}

fn ct_eq(a: &str, b: &str) -> bool {
    a.len() == b.len() && a.bytes().zip(b.bytes()).fold(0u8, |d, (x, y)| d | (x ^ y)) == 0
}

#[derive(Deserialize)]
#[serde(tag = "t")]
enum In {
    #[serde(rename = "hello")]
    Hello { id: String, name: String, token: Option<String>, secret: Option<String> },
    #[serde(rename = "mouse.move")]
    MouseMove { dx: i32, dy: i32 },
    #[serde(rename = "mouse.click")]
    MouseClick { button: String, #[serde(default)] double: bool },
    #[serde(rename = "mouse.button")]
    MouseButton { button: String, down: bool },
    #[serde(rename = "mouse.scroll")]
    Scroll { #[serde(default)] dx: i32, dy: i32 },
    #[serde(rename = "key")]
    Key { key: String, #[serde(default)] mods: Vec<String> },
    #[serde(rename = "text")]
    Text { text: String },
    #[serde(rename = "media")]
    Media { action: String },
    #[serde(rename = "volume")]
    Volume { action: String },
    #[serde(rename = "brightness")]
    Brightness { action: String },
    #[serde(rename = "brightness.set")]
    BrightnessSet { percent: i32 },
    #[serde(rename = "brightness.get")]
    BrightnessGet,
    #[serde(rename = "mirror.start")]
    MirrorStart {
        #[serde(default)]
        title: Option<String>,
        #[serde(default)]
        width: Option<i32>,
        #[serde(default)]
        height: Option<i32>,
    },
    #[serde(rename = "mirror.frame")]
    MirrorFrame { data: String },
    #[serde(rename = "mirror.stop")]
    MirrorStop,
    #[serde(rename = "screen.capture")]
    ScreenCapture,
    #[serde(rename = "laser.start")]
    LaserStart,
    #[serde(rename = "laser.move")]
    LaserMove { dx: i32, dy: i32 },
    #[serde(rename = "laser.stop")]
    LaserStop,
    #[serde(rename = "ws.list")]
    WsList,
    #[serde(rename = "ws.switch")]
    WsSwitch { id: i64 },
    #[serde(rename = "win.list")]
    WinList,
    #[serde(rename = "win.focus")]
    WinFocus { address: String },
    #[serde(rename = "cmd.list")]
    CmdList,
    #[serde(rename = "cmd.run")]
    CmdRun { name: String },
    #[serde(rename = "clip.set")]
    ClipSet { text: String },
    #[serde(rename = "clip.get")]
    ClipGet,
    #[serde(rename = "file.offer")]
    FileOffer { id: String, name: String, size: u64 },
    #[serde(rename = "file.chunk")]
    FileChunk { id: String, data: String },
    #[serde(rename = "file.end")]
    FileEnd { id: String },
    #[serde(rename = "battery")]
    Battery { pct: i64, charging: bool },
    #[serde(rename = "notif")]
    Notif { app: String, title: String, text: String },
    #[serde(rename = "sms")]
    Sms { from: String, body: String },
    #[serde(rename = "ping")]
    Ping,
}

struct Incoming {
    file: File,
    part: PathBuf,
    dest: PathBuf,
    size: u64,
    got: u64,
}

pub async fn run(state: Arc<State>) -> Result<()> {
    let addr = ("0.0.0.0", state.cfg.port);
    let l = match TcpListener::bind(addr).await {
        Ok(l) => l,
        Err(e) if e.kind() == std::io::ErrorKind::AddrInUse => {
            anyhow::bail!(
                "Port {} is already in use!\n\
                 Another process (e.g. kdeconnectd or another hyprconnect instance) is holding this port.\n\
                 Solutions:\n\
                 1. If kdeconnect is running, stop it: killall kdeconnectd\n\
                 2. Or change \"port\" in ~/.config/hyprconnect/config.json (e.g. \"port\": 5716)",
                state.cfg.port
            );
        }
        Err(e) => return Err(e.into()),
    };
    eprintln!("listening on :{}  fingerprint {}", state.cfg.port, state.ident.fingerprint);
    loop {
        let (tcp, peer) = l.accept().await?;
        let st = state.clone();
        tokio::spawn(async move {
            if let Err(e) = handle(st, tcp).await {
                eprintln!("[{peer}] closed: {e}");
            }
        });
    }
}

fn authenticate(st: &State, id: &str, name: &str, token: Option<&str>, secret: Option<&str>)
    -> Result<Option<String>, &'static str>
{
    if let Some(sec) = secret {
        let d = st.devices.lock().unwrap();
        return match d.paired.get(id) {
            Some(dev) if ct_eq(&dev.secret_hash, &sha(sec)) => Ok(None),
            _ => Err("not paired"),
        };
    }
    if let Some(tok) = token {
        let mut p = st.pairing.lock().unwrap();
        let ok = matches!(&*p, Some((t, exp)) if Instant::now() < *exp && ct_eq(t, tok));
        if !ok {
            return Err("bad or expired pairing token");
        }
        *p = None; // single use
        drop(p);
        let mut b = [0u8; 32];
        rand::rngs::OsRng.fill_bytes(&mut b);
        let secret = hex::encode(b);
        let mut d = st.devices.lock().unwrap();
        d.paired.insert(id.to_string(), Device { name: name.to_string(), secret_hash: sha(&secret) });
        let _ = config::save_devices(&d);
        return Ok(Some(secret));
    }
    Err("no credentials")
}

async fn handle(st: Arc<State>, tcp: TcpStream) -> Result<()> {
    tcp.set_nodelay(true)?;
    let tls = tokio::time::timeout(Duration::from_secs(10), st.ident.acceptor.accept(tcp)).await??;
    let (rd, mut wr) = tokio::io::split(tls);
    let mut rd = BufReader::new(rd);
    let mut buf = String::new();

    // ---- handshake
    let n = tokio::time::timeout(Duration::from_secs(10), (&mut rd).take(MAX_LINE).read_line(&mut buf)).await??;
    if n == 0 {
        return Ok(());
    }
    let (id, name, new_secret) = match serde_json::from_str::<In>(&buf) {
        Ok(In::Hello { id, name, token, secret }) => {
            let name: String = name.chars().take(64).collect();
            if id.is_empty() || id.len() > 64 {
                return Ok(());
            }
            match authenticate(&st, &id, &name, token.as_deref(), secret.as_deref()) {
                Ok(s) => (id, name, s),
                Err(why) => {
                    tokio::time::sleep(Duration::from_secs(1)).await; // slow down guessing
                    let _ = wr.write_all(format!("{}\n", json!({"t":"error","msg":why})).as_bytes()).await;
                    return Ok(());
                }
            }
        }
        _ => return Ok(()),
    };

    let host = std::fs::read_to_string("/etc/hostname").unwrap_or_default().trim().to_string();
    let mut welcome = json!({"t":"welcome","host":host});
    if let Some(s) = &new_secret {
        welcome["secret"] = json!(s);
    }
    wr.write_all(format!("{welcome}\n").as_bytes()).await?;

    let (tx, mut rx) = mpsc::channel::<Value>(64);
    st.sessions.lock().unwrap().insert(id.clone(), Session { name: name.clone(), tx: tx.clone(), battery: None });
    eprintln!("connected: {name} ({id})");
    sys::notify("HyprConnect", "Phone connected", &name);

    let writer = tokio::spawn(async move {
        while let Some(v) = rx.recv().await {
            let mut s = v.to_string();
            s.push('\n');
            if wr.write_all(s.as_bytes()).await.is_err() {
                break;
            }
        }
    });

    let _ = tx.send(sys::ws_state().await).await;
    if let Some(b) = sys::get_brightness().await {
        let _ = tx.send(json!({"t":"brightness.status","percent":b})).await;
    }

    let mut files: HashMap<String, Incoming> = HashMap::new();
    let res: Result<()> = async {
        loop {
            buf.clear();
            let n = (&mut rd).take(MAX_LINE).read_line(&mut buf).await?;
            if n == 0 {
                return Ok(());
            }
            if !buf.ends_with('\n') {
                anyhow::bail!("line too long");
            }
            match serde_json::from_str::<In>(&buf) {
                Ok(m) => on_msg(&st, &id, &tx, &mut files, m).await,
                Err(e) => eprintln!("bad message: {e}"),
            }
        }
    }
    .await;

    for (_, f) in files {
        let _ = tokio::fs::remove_file(f.part).await;
    }
    sys::mirror_stop().await;
    sys::laser_stop().await;
    // only drop our own session entry
    {
        let mut s = st.sessions.lock().unwrap();
        if s.get(&id).map(|x| x.tx.same_channel(&tx)).unwrap_or(false) {
            s.remove(&id);
        }
    }
    writer.abort();
    eprintln!("disconnected: {name}");
    res
}

fn safe_name(name: &str) -> String {
    let base = Path::new(name).file_name().and_then(|s| s.to_str()).unwrap_or("file");
    let s: String = base.chars().map(|c| if c.is_control() || c == '/' || c == '\\' { '_' } else { c }).collect();
    let s = s.trim_start_matches('.').to_string();
    if s.is_empty() { "file".into() } else { s.chars().take(200).collect() }
}

fn unique(dir: &Path, name: &str) -> PathBuf {
    let mut p = dir.join(name);
    let mut i = 1;
    while p.exists() {
        let (stem, ext) = match name.rsplit_once('.') {
            Some((s, e)) => (s.to_string(), format!(".{e}")),
            None => (name.to_string(), String::new()),
        };
        p = dir.join(format!("{stem} ({i}){ext}"));
        i += 1;
    }
    p
}

async fn on_msg(st: &Arc<State>, id: &str, tx: &mpsc::Sender<Value>, files: &mut HashMap<String, Incoming>, m: In) {
    let with_input = |f: &mut dyn FnMut(&mut Input) -> Result<()>| {
        if let Some(i) = st.input.lock().unwrap().as_mut() {
            if let Err(e) = f(i) {
                eprintln!("input: {e}");
            }
        }
    };
    match m {
        In::Hello { .. } => {}
        In::MouseMove { dx, dy } => with_input(&mut |i| i.mouse_move(dx, dy)),
        In::MouseClick { button, double } => with_input(&mut |i| i.click(&button, double)),
        In::MouseButton { button, down } => with_input(&mut |i| i.mouse_button(&button, down)),
        In::Scroll { dx, dy } => with_input(&mut |i| i.scroll(dx, dy)),
        In::Key { key, mods } => with_input(&mut |i| i.key(&key, &mods)),
        In::Text { text } => {
            let mut all_typed = false;
            if text.chars().all(|c| c.is_ascii()) {
                with_input(&mut |i| {
                    if let Ok(()) = i.type_str(&text) {
                        all_typed = true;
                    }
                    Ok(())
                });
            }
            if !all_typed {
                if !sys::try_wtype(&text).await {
                    sys::clip_set(&text).await;
                    tokio::time::sleep(tokio::time::Duration::from_millis(40)).await;
                    with_input(&mut |i| i.key("v", &["ctrl".to_string()]));
                }
            }
        }
        In::Media { action } => sys::media(&action),
        In::Volume { action } => sys::volume(&action),
        In::Brightness { action } => {
            sys::brightness(&action);
            tokio::time::sleep(Duration::from_millis(50)).await;
            if let Some(b) = sys::get_brightness().await {
                let _ = tx.send(json!({"t":"brightness.status","percent":b})).await;
            }
        }
        In::BrightnessSet { percent } => {
            sys::brightness_set(percent);
            let _ = tx.send(json!({"t":"brightness.status","percent":percent})).await;
        }
        In::BrightnessGet => {
            if let Some(b) = sys::get_brightness().await {
                let _ = tx.send(json!({"t":"brightness.status","percent":b})).await;
            }
        }
        In::MirrorStart { title, width, height } => {
            let t = title.unwrap_or_else(|| "HyprConnect Phone Screen".into());
            let w = width.unwrap_or(420);
            let h = height.unwrap_or(750);
            sys::mirror_start(&t, w, h, tx.clone()).await;
        }
        In::MirrorFrame { data } => sys::mirror_frame(&data).await,
        In::MirrorStop => sys::mirror_stop().await,
        In::ScreenCapture => {
            if let Some(f) = sys::capture_screen().await {
                let _ = tx.send(json!({"t":"screen.frame","data":f})).await;
            }
        }
        In::LaserStart => sys::laser_start().await,
        In::LaserMove { dx, dy } => sys::laser_move(dx, dy).await,
        In::LaserStop => sys::laser_stop().await,
        In::WsList => { let _ = tx.send(sys::ws_state().await).await; }
        In::WsSwitch { id } => sys::ws_switch(id),
        In::WinList => { let _ = tx.send(sys::win_list().await).await; }
        In::WinFocus { address } => sys::win_focus(&address),
        In::CmdList => {
            let names: Vec<&String> = st.cfg.commands.keys().collect();
            let _ = tx.send(json!({"t":"cmd.list","names":names})).await;
        }
        In::CmdRun { name } => match st.cfg.commands.get(&name) {
            Some(c) => sys::run_shell(c),
            None => eprintln!("unknown command {name}"),
        },
        In::ClipSet { text } => sys::clip_set(&text).await,
        In::ClipGet => { let _ = tx.send(json!({"t":"clip","text":sys::clip_get().await})).await; }
        In::Battery { pct, charging } => {
            if let Some(s) = st.sessions.lock().unwrap().get_mut(id) {
                s.battery = Some((pct, charging));
            }
        }
        In::Notif { app, title, text } => sys::notify(&format!("Phone: {app}"), &title, &text),
        In::Sms { from, body } => sys::notify("Phone SMS", &format!("SMS from {from}"), &body),
        In::Ping => { let _ = tx.send(json!({"t":"pong"})).await; }

        In::FileOffer { id: fid, name, size } => {
            if files.len() >= 4 || size > MAX_FILE {
                let _ = tx.send(json!({"t":"file.reject","id":fid})).await;
                return;
            }
            let dir = PathBuf::from(&st.cfg.download_dir);
            if tokio::fs::create_dir_all(&dir).await.is_err() {
                let _ = tx.send(json!({"t":"file.reject","id":fid})).await;
                return;
            }
            let dest = unique(&dir, &safe_name(&name));
            let part = dest.with_extension(format!("{}part", dest.extension().and_then(|e| e.to_str()).map(|e| format!("{e}.")).unwrap_or_default()));
            match File::create(&part).await {
                Ok(file) => { files.insert(fid, Incoming { file, part, dest, size, got: 0 }); }
                Err(_) => { let _ = tx.send(json!({"t":"file.reject","id":fid})).await; }
            }
        }
        In::FileChunk { id: fid, data } => {
            let Some(f) = files.get_mut(&fid) else { return };
            let Ok(bytes) = B64.decode(data) else { return };
            f.got += bytes.len() as u64;
            if f.got > f.size || f.file.write_all(&bytes).await.is_err() {
                if let Some(f) = files.remove(&fid) { let _ = tokio::fs::remove_file(f.part).await; }
            }
        }
        In::FileEnd { id: fid } => {
            if let Some(mut f) = files.remove(&fid) {
                let _ = f.file.flush().await;
                drop(f.file);
                if f.got == f.size && tokio::fs::rename(&f.part, &f.dest).await.is_ok() {
                    sys::notify("HyprConnect", "File received", &f.dest.to_string_lossy());
                } else {
                    let _ = tokio::fs::remove_file(&f.part).await;
                }
            }
        }
    }
}

/// Stream a local file to the phone (called from IPC).
pub async fn send_file(tx: mpsc::Sender<Value>, path: PathBuf) -> Result<String> {
    let mut f = File::open(&path).await?;
    let size = f.metadata().await?.len();
    let name = path.file_name().and_then(|s| s.to_str()).unwrap_or("file").to_string();
    let mut idb = [0u8; 8];
    rand::rngs::OsRng.fill_bytes(&mut idb);
    let fid = hex::encode(idb);
    tx.send(json!({"t":"file.offer","id":fid,"name":name,"size":size})).await?;
    tokio::spawn(async move {
        let mut buf = vec![0u8; CHUNK];
        loop {
            let n = match f.read(&mut buf).await { Ok(0) | Err(_) => break, Ok(n) => n };
            if tx.send(json!({"t":"file.chunk","id":fid,"data":B64.encode(&buf[..n])})).await.is_err() {
                return;
            }
        }
        let _ = tx.send(json!({"t":"file.end","id":fid})).await;
    });
    Ok(name)
}

/// Follow Hyprland's event socket and push workspace changes to phones.
pub async fn hypr_events(st: Arc<State>) {
    loop {
        let sig = std::env::var("HYPRLAND_INSTANCE_SIGNATURE").unwrap_or_default();
        let rt = std::env::var("XDG_RUNTIME_DIR").unwrap_or_default();
        let path = format!("{rt}/hypr/{sig}/.socket2.sock");
        if let Ok(s) = tokio::net::UnixStream::connect(&path).await {
            let mut lines = BufReader::new(s).lines();
            while let Ok(Some(l)) = lines.next_line().await {
                let ev = l.split(">>").next().unwrap_or("");
                if matches!(ev, "workspace" | "workspacev2" | "createworkspace" | "destroyworkspace"
                    | "openwindow" | "closewindow" | "movewindow") {
                    if !st.sessions.lock().unwrap().is_empty() {
                        st.broadcast(sys::ws_state().await).await;
                    }
                }
            }
        }
        tokio::time::sleep(Duration::from_secs(5)).await;
    }
}
