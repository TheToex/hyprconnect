<div align="center">

  <img src="icons/hyprconnect.svg" width="112" height="112" alt="HyprConnect Logo" />

  # HyprConnect

  **Wayland-native remote control and desktop companion tailored specifically for Hyprland.**

  [![Version](https://img.shields.io/badge/version-1.0.0-89dceb?style=for-the-badge)](https://github.com/TheToex/hyprconnect/releases)
  [![Rust](https://img.shields.io/badge/daemon-Rust-orange?style=for-the-badge&logo=rust)](daemon/)
  [![Android](https://img.shields.io/badge/app-Kotlin%20%7C%20Compose-3DDC84?style=for-the-badge&logo=android)](android/)
  [![Hyprland](https://img.shields.io/badge/compositor-Hyprland-00c3ff?style=for-the-badge)](https://hyprland.org)
  [![License](https://img.shields.io/badge/license-MIT-blue?style=for-the-badge)](LICENSE)
  [![Donate](https://img.shields.io/badge/Donate-Support%20Project-ea76cb?style=for-the-badge&logo=githubsponsors&logoColor=white)](https://thetoex.github.io/donate/)

</div>

---

## 📖 Overview

**HyprConnect** is an independent, lightweight, ground-up companion that bridges your Android smartphone and your Linux desktop running the **Hyprland** Wayland compositor. 

Unlike traditional solutions, HyprConnect has **zero KDE dependencies** and is built specifically for Wayland and Hyprland's native architecture. It features a blazing-fast, secure Rust daemon, a sleek StatusNotifierItem system tray applet, and a modern Android application designed with Jetpack Compose in the **Catppuccin Mocha** palette.

---

## ✨ Features

### 📱 Remote Input & Trackpad
- **Ultra-Low Latency Trackpad**: Coalesced ~200Hz packet streaming for buttery smooth pointer motion, tap-to-click, secondary click, and dual-finger scroll.
- **Kernel-Level Input Injection**: Uses Linux `/dev/uinput` for direct hardware-level mouse and keyboard event dispatch.
- **Live Typing**: Real-time keystroke transmission directly to active Wayland windows with full Unicode and multi-language support.

### 📽 Presentation Remote & Gyro Laser
- **Slide Controls**: Fullscreen, Next Slide, Previous Slide, and Black Screen shortcuts.
- **Virtual Laser Pointer**: Turn your phone into an interactive pointer—uses device gyroscope sensors to project a smooth, non-intrusive translucent laser dot over Hyprland via a native transparent Wayland layer.
- **Presentation Timer**: Clean, free-running presentation stopwatch to keep you on schedule.

### 🪟 Hyprland Workspace & Window Integration
- **Real-time Event Streaming**: Subscribes directly to Hyprland's IPC UNIX socket (`$XDG_RUNTIME_DIR/hypr/$HYPRLAND_INSTANCE_SIGNATURE/.socket2.sock`).
- **Workspace Navigation**: Instant one-touch switching between active and empty workspaces (1 to 4).
- **Window Focusing**: Browse currently open windows and focus them on your desktop with a single tap.

### 🪞 Live Screen Mirroring (Phone → PC)
- Live wireless projection of your Android screen into a dedicated floating, hardware-accelerated Wayland window (`hyprconnect-mirror`).
- High-efficiency streaming with automatic dimension scaling and low network footprint.

### 🎵 Media & Brightness Control
- **MPRIS Player Integration**: Full multimedia playback controls (Play/Pause, Next, Previous, Volume) via `playerctl` and PipeWire/WirePlumber (`wpctl`).
- **Synchronized Screen Brightness**: Real-time synchronization of current laptop display brightness using `brightnessctl`.

### 📁 Bi-Directional High-Speed File Transfer
- Stream files (documents, images, videos up to 8GB) seamlessly between phone and PC with progress tracking.
- Automatic destination directory handling (`~/Downloads/hyprconnect`).

### 📋 Clipboard & Notification Sync
- **Shared Clipboard**: Instant bidirectional copy-paste synchronization powered by `wl-clipboard`.
- **Desktop Notification Relay**: Phone alerts, incoming calls, and text messages forwarded natively to your desktop notification daemon (AGS, SwayNC, Dunst, Mako) with the custom HyprConnect logo.
- **SMS from Desktop**: Send SMS text messages through your phone directly from the CLI or system tray.

### 🛎 StatusNotifierItem System Tray Applet
- Modern tray icon matching the crisp white Hyprland origami branding.
- Live battery percentage indicator with charging state detection.
- Right-click quick menu for file dispatch, SMS composer, clipboard push, and instant QR pairing.

---

## 🔒 Security Architecture

- **End-to-End TLS**: All traffic is encrypted with TLS 1.3 using `rustls`.
- **Zero Cloud Reliance**: Works completely within your local network (Wi-Fi, hotspot, or USB tethering).
- **Anti-MITM Certificate Pinning**: The daemon generates a local cryptographic certificate. The pairing QR code encodes its SHA-256 fingerprint; the phone strictly verifies and pins this certificate.
- **Time-Limited Pairing**: Pairing tokens are 16-byte cryptographically secure random values expiring in 120 seconds.
- **Safe Protocol Boundaries**: Strict line size limits, path sanitization against directory traversal, and hashed credentials (`0600` permissions on secret stores).

---

## 📦 Project Structure

```
hyprconnect/
├── daemon/          # Rust daemon & CLI engine (single binary: hyprconnect)
├── android/         # Android app (Kotlin, Jetpack Compose, Material 3)
├── bin/             # Helper scripts (laser overlay, mirror player, system tray)
├── icons/           # Multi-size PNGs and scalable SVG vectors
└── linux-extras/    # udev rules and systemd service templates
```

---

## 🚀 Getting Started (Linux)

### 1. Install Dependencies

#### Arch Linux
```bash
sudo pacman -S --needed \
    rust cargo \
    wtype playerctl brightnessctl wl-clipboard libnotify qrencode \
    python python-gobject gtk3 libayatana-appindicator
```

### 2. Configure `uinput` Permissions

HyprConnect uses `/dev/uinput` to inject touch and key events without needing root privileges at runtime.

```bash
# Install udev rule
sudo install -Dm644 linux-extras/99-hyprconnect-uinput.rules /etc/udev/rules.d/

# Add your user to the input group
sudo usermod -aG input $USER

# Load uinput module automatically
echo uinput | sudo tee /etc/modules-load.d/uinput.conf
sudo modprobe uinput
sudo udevadm control --reload && sudo udevadm trigger
```
> [!NOTE]
> Log out and log back in for the new `input` group membership to take effect.

### 3. Build & Install Daemon

```bash
# Build release binary
cd daemon
cargo build --release

# Install binary and helper scripts
install -Dm755 target/release/hyprconnect ~/.local/bin/hyprconnect
install -Dm755 ../bin/hyprconnect-tray ~/.local/bin/hyprconnect-tray

# Install icons
mkdir -p ~/.local/share/hyprconnect/icons ~/.local/share/icons/hicolor/scalable/apps
cp ../icons/* ~/.local/share/hyprconnect/icons/
cp ../icons/hyprconnect.svg ~/.local/share/icons/hicolor/scalable/apps/hyprconnect.svg
gtk-update-icon-cache -f -t ~/.local/share/icons/hicolor 2>/dev/null || true
```

### 4. Hyprland Configuration

Add HyprConnect to your `~/.config/hypr/hyprland.conf` to launch automatically upon login:

```ini
exec-once = hyprconnect daemon
```

Make sure TCP port `5716` is permitted on your local firewall (if active):
```bash
sudo ufw allow in proto tcp to any port 5716 comment 'HyprConnect daemon'
```

---

## 📱 Android App Setup

1. Grab the latest `app-debug.apk` from the **[Releases](https://github.com/TheToex/hyprconnect/releases)** tab (or open `android/` in Android Studio and run `./gradlew assembleDebug`).
2. Install the APK on your Android device (Android 10+ / API 29+ recommended).
3. Ensure both devices are on the same local network (Wi-Fi or phone Hotspot).
4. Run `hyprconnect pair` in your terminal (or choose **Pair New Device** from the desktop system tray) and scan the QR code using the app.
5. Grant notification and SMS permissions in the app's settings if you wish to relay alerts.

---

## 💻 CLI Reference

```text
USAGE:
  hyprconnect daemon              Run the background daemon & auto-spawn tray
  hyprconnect tray                Run the system tray applet standalone
  hyprconnect pair                Display an ASCII QR code to pair a device (valid 2 min)
  hyprconnect devices             List paired devices, live connection, and battery level
  hyprconnect send <file>         Send a file to the active phone
  hyprconnect sms <number> <text> Send an SMS through the phone
  hyprconnect clip                Push desktop clipboard to the phone
  hyprconnect unpair <device-id>  Forget a paired device
```

---

## ⚙️ Custom Desktop Commands

You can configure desktop commands executable from your phone in `~/.config/hyprconnect/config.json`:

```json
{
  "port": 5716,
  "download_dir": "/home/toex/Downloads/hyprconnect",
  "commands": {
    "Lock Screen": "hyprlock",
    "Take Screenshot": "grim - | wl-copy",
    "Suspend System": "systemctl suspend"
  }
}
```
*The phone only references the command by title; raw shell strings are never transmitted over the network.*

---

## 💖 Support & Donation

If you find **HyprConnect** useful and want to support ongoing development, consider buying a coffee or contributing to project maintenance:

<div align="center">

  [![Donate](https://img.shields.io/badge/Donate-Support%20HyprConnect-ea76cb?style=for-the-badge&logo=githubsponsors&logoColor=white)](https://thetoex.github.io/donate/)

  👉 **[https://thetoex.github.io/donate/](https://thetoex.github.io/donate/)**

</div>

Every bit of support is greatly appreciated!

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
Feel free to contribute, open issues, or submit pull requests!
