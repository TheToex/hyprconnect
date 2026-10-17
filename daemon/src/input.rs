//! Virtual keyboard + mouse through /dev/uinput.
//! Needs write access to /dev/uinput (see udev rule in README).
use anyhow::{anyhow, Result};
use evdev::{
    uinput::{VirtualDevice, VirtualDeviceBuilder},
    AttributeSet, EventType, InputEvent, Key, RelativeAxisType,
};

pub struct Input {
    dev: VirtualDevice,
}

const LETTERS: [Key; 26] = [
    Key::KEY_A, Key::KEY_B, Key::KEY_C, Key::KEY_D, Key::KEY_E, Key::KEY_F, Key::KEY_G,
    Key::KEY_H, Key::KEY_I, Key::KEY_J, Key::KEY_K, Key::KEY_L, Key::KEY_M, Key::KEY_N,
    Key::KEY_O, Key::KEY_P, Key::KEY_Q, Key::KEY_R, Key::KEY_S, Key::KEY_T, Key::KEY_U,
    Key::KEY_V, Key::KEY_W, Key::KEY_X, Key::KEY_Y, Key::KEY_Z,
];
const DIGITS: [Key; 10] = [
    Key::KEY_0, Key::KEY_1, Key::KEY_2, Key::KEY_3, Key::KEY_4,
    Key::KEY_5, Key::KEY_6, Key::KEY_7, Key::KEY_8, Key::KEY_9,
];
const FKEYS: [Key; 12] = [
    Key::KEY_F1, Key::KEY_F2, Key::KEY_F3, Key::KEY_F4, Key::KEY_F5, Key::KEY_F6,
    Key::KEY_F7, Key::KEY_F8, Key::KEY_F9, Key::KEY_F10, Key::KEY_F11, Key::KEY_F12,
];

pub fn key_by_name(name: &str) -> Option<Key> {
    let n = name.to_lowercase();
    if n.len() == 1 {
        let c = n.as_bytes()[0];
        if c.is_ascii_lowercase() {
            return Some(LETTERS[(c - b'a') as usize]);
        }
        if c.is_ascii_digit() {
            return Some(DIGITS[(c - b'0') as usize]);
        }
    }
    if let Some(num) = n.strip_prefix('f').and_then(|s| s.parse::<usize>().ok()) {
        if (1..=12).contains(&num) {
            return Some(FKEYS[num - 1]);
        }
    }
    Some(match n.as_str() {
        "enter" | "return" => Key::KEY_ENTER,
        "esc" | "escape" => Key::KEY_ESC,
        "backspace" => Key::KEY_BACKSPACE,
        "tab" => Key::KEY_TAB,
        "space" => Key::KEY_SPACE,
        "left" => Key::KEY_LEFT,
        "right" => Key::KEY_RIGHT,
        "up" => Key::KEY_UP,
        "down" => Key::KEY_DOWN,
        "home" => Key::KEY_HOME,
        "end" => Key::KEY_END,
        "pageup" => Key::KEY_PAGEUP,
        "pagedown" => Key::KEY_PAGEDOWN,
        "delete" => Key::KEY_DELETE,
        "insert" => Key::KEY_INSERT,
        "ctrl" | "control" => Key::KEY_LEFTCTRL,
        "shift" => Key::KEY_LEFTSHIFT,
        "alt" => Key::KEY_LEFTALT,
        "super" | "meta" => Key::KEY_LEFTMETA,
        "minus" => Key::KEY_MINUS,
        "equal" => Key::KEY_EQUAL,
        "dot" => Key::KEY_DOT,
        _ => return None,
    })
}

impl Input {
    pub fn new() -> Result<Self> {
        let mut keys = AttributeSet::<Key>::new();
        for code in 1..249u16 {
            keys.insert(Key::new(code));
        }
        keys.insert(Key::BTN_LEFT);
        keys.insert(Key::BTN_RIGHT);
        keys.insert(Key::BTN_MIDDLE);
        let mut rel = AttributeSet::<RelativeAxisType>::new();
        rel.insert(RelativeAxisType::REL_X);
        rel.insert(RelativeAxisType::REL_Y);
        rel.insert(RelativeAxisType::REL_WHEEL);
        rel.insert(RelativeAxisType::REL_HWHEEL);
        let dev = VirtualDeviceBuilder::new()
            .map_err(|e| anyhow!("cannot open /dev/uinput ({e}); see README for the udev rule"))?
            .name("hyprconnect virtual input")
            .with_keys(&keys)?
            .with_relative_axes(&rel)?
            .build()?;
        Ok(Input { dev })
    }

    fn rel(&mut self, axis: RelativeAxisType, v: i32) -> Result<()> {
        self.dev
            .emit(&[InputEvent::new(EventType::RELATIVE, axis.0, v)])?;
        Ok(())
    }

    fn key_event(&mut self, k: Key, down: bool) -> Result<()> {
        self.dev
            .emit(&[InputEvent::new(EventType::KEY, k.code(), down as i32)])?;
        Ok(())
    }

    pub fn mouse_move(&mut self, dx: i32, dy: i32) -> Result<()> {
        let (dx, dy) = (dx.clamp(-2000, 2000), dy.clamp(-2000, 2000));
        if dx != 0 {
            self.rel(RelativeAxisType::REL_X, dx)?;
        }
        if dy != 0 {
            self.rel(RelativeAxisType::REL_Y, dy)?;
        }
        Ok(())
    }

    pub fn scroll(&mut self, dx: i32, dy: i32) -> Result<()> {
        if dy != 0 {
            self.rel(RelativeAxisType::REL_WHEEL, -dy.clamp(-50, 50))?;
        }
        if dx != 0 {
            self.rel(RelativeAxisType::REL_HWHEEL, dx.clamp(-50, 50))?;
        }
        Ok(())
    }

    fn button(name: &str) -> Key {
        match name {
            "right" => Key::BTN_RIGHT,
            "middle" => Key::BTN_MIDDLE,
            _ => Key::BTN_LEFT,
        }
    }

    pub fn mouse_button(&mut self, name: &str, down: bool) -> Result<()> {
        self.key_event(Self::button(name), down)
    }

    pub fn click(&mut self, name: &str, double: bool) -> Result<()> {
        let b = Self::button(name);
        for _ in 0..(if double { 2 } else { 1 }) {
            self.key_event(b, true)?;
            self.key_event(b, false)?;
        }
        Ok(())
    }

    /// Press `key` while holding `mods` (names like "ctrl", "shift").
    pub fn key(&mut self, key: &str, mods: &[String]) -> Result<()> {
        let k = key_by_name(key).ok_or_else(|| anyhow!("unknown key {key}"))?;
        let ms: Vec<Key> = mods.iter().filter_map(|m| key_by_name(m)).collect();
        for m in &ms {
            self.key_event(*m, true)?;
        }
        self.key_event(k, true)?;
        self.key_event(k, false)?;
        for m in ms.iter().rev() {
            self.key_event(*m, false)?;
        }
        Ok(())
    }
}
