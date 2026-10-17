#!/usr/bin/env bash
# ==============================================================================
#  HyprConnect Installer for Linux / Hyprland
#  One-line install:
#    curl -sSL https://raw.githubusercontent.com/TheToex/hyprconnect/main/install.sh | bash
# ==============================================================================
set -euo pipefail

# ANSI Palette (Catppuccin Mocha & HyprSky)
CYAN='\033[38;2;137;220;235m'
BLUE='\033[38;2;116;199;236m'
GREEN='\033[38;2;166;227;161m'
YELLOW='\033[38;2;249;226;175m'
RED='\033[38;2;243;139;168m'
MAGENTA='\033[38;2;245;194;231m'
BOLD='\033[1m'
RESET='\033[0m'

REPO_URL="https://github.com/TheToex/hyprconnect.git"
INSTALL_BIN_DIR="${HOME}/.local/bin"
INSTALL_SHARE_DIR="${HOME}/.local/share"
INSTALL_APP_DIR="${INSTALL_SHARE_DIR}/applications"
INSTALL_ICON_DIR="${INSTALL_SHARE_DIR}/hyprconnect/icons"
INSTALL_SERVICE_DIR="${HOME}/.config/systemd/user"

# Ensure Cargo / Local bin in PATH during execution
if [[ -d "${HOME}/.cargo/bin" && ":$PATH:" != *":${HOME}/.cargo/bin:"* ]]; then
    export PATH="${HOME}/.cargo/bin:${PATH}"
fi
if [[ -d "${INSTALL_BIN_DIR}" && ":$PATH:" != *":${INSTALL_BIN_DIR}:"* ]]; then
    export PATH="${INSTALL_BIN_DIR}:${PATH}"
fi

print_banner() {
    cat << "EOF"
    __  __                  ______                            __ 
   / / / /_  ______  _____ / ____/___  ____  ____  ___  _____/ /_
  / /_/ / / / / __ \/ ___// /   / __ \/ __ \/ __ \/ _ \/ ___/ __/
 / __  / /_/ / /_/ / /   / /___/ /_/ / / / / / / /  __/ /__/ /_  
/_/ /_/\__, / .___/_/    \____/\____/_/ /_/_/ /_/\___/\___/\__/  
      /____/_/                                                   
EOF
    echo -e "${CYAN}${BOLD}  Wayland-native Remote Control & Companion for Hyprland${RESET}"
    echo -e "${BLUE}  Repository: https://github.com/TheToex/hyprconnect${RESET}\n"
}

info() {
    echo -e "${CYAN}[*]${RESET} $1"
}

success() {
    echo -e "${GREEN}[✔]${RESET} $1"
}

warn() {
    echo -e "${YELLOW}[!]${RESET} $1"
}

err() {
    echo -e "${RED}[✘]${RESET} $1" >&2
}

prompt_yes_no() {
    local prompt="$1"
    local default="${2:-y}"
    local response=""
    if [ -t 0 ]; then
        read -r -p "$prompt " response || response="$default"
    elif [ -e /dev/tty ]; then
        read -r -p "$prompt " response < /dev/tty || response="$default"
    else
        response="$default"
    fi
    response="${response:-$default}"
    [[ "$response" =~ ^([yY][eE][sS]|[yY])$ ]]
}

# Uninstall routine
if [[ "${1:-}" == "--uninstall" ]]; then
    info "Uninstalling HyprConnect from user environment..."
    systemctl --user stop hyprconnect.service 2>/dev/null || true
    systemctl --user disable hyprconnect.service 2>/dev/null || true
    pkill -f hyprconnect || true
    pkill -f hyprconnect-tray || true

    rm -f "${INSTALL_BIN_DIR}/hyprconnect"
    rm -f "${INSTALL_BIN_DIR}/hyprconnect-tray"
    rm -f "${INSTALL_APP_DIR}/hyprconnect.desktop"
    rm -f "${INSTALL_SERVICE_DIR}/hyprconnect.service"
    rm -rf "${INSTALL_SHARE_DIR}/hyprconnect"
    rm -f "${INSTALL_SHARE_DIR}/icons/hicolor/scalable/apps/hyprconnect.svg"
    rm -f "${INSTALL_SHARE_DIR}/icons/hicolor/scalable/apps/hyprconnect-tray-white.svg"
    rm -f "${INSTALL_SHARE_DIR}/pixmaps/hyprconnect.png"
    rm -f "${INSTALL_SHARE_DIR}/pixmaps/hyprconnect-tray-white.png"

    gtk-update-icon-cache -f -t "${INSTALL_SHARE_DIR}/icons/hicolor" 2>/dev/null || true
    update-desktop-database "${INSTALL_APP_DIR}" 2>/dev/null || true

    success "HyprConnect has been cleanly uninstalled."
    exit 0
fi

print_banner

# Prevent running script as root directly
if [[ "${EUID}" -eq 0 ]]; then
    err "Please do not run this installer as root/sudo directly."
    err "The installer runs in user space and prompts for sudo only when setting udev rules."
    exit 1
fi

# Detect package manager & check dependencies
check_dependencies() {
    info "Checking system dependencies..."
    local missing_pkgs=()

    # 1. Rust / Cargo
    if ! command -v cargo &>/dev/null; then
        if command -v pacman &>/dev/null; then
            missing_pkgs+=(rust cargo)
        else
            err "Cargo not found. Please install Rust: https://rustup.rs"
            exit 1
        fi
    fi

    # 2. Python 3 & GTK/GObject
    if ! command -v python3 &>/dev/null; then
        missing_pkgs+=(python)
    fi

    if ! python3 -c "import gi; gi.require_version('Gtk', '3.0')" &>/dev/null; then
        if command -v pacman &>/dev/null; then
            missing_pkgs+=(python-gobject gtk3)
        fi
    fi

    # 3. Tray AppIndicator
    if ! python3 -c "import gi; (gi.require_version('AyatanaAppIndicator3', '0.1') or gi.require_version('AppIndicator3', '0.1'))" &>/dev/null; then
        if command -v pacman &>/dev/null; then
            missing_pkgs+=(libayatana-appindicator)
        fi
    fi

    # 4. Helpers
    if ! command -v playerctl &>/dev/null && command -v pacman &>/dev/null; then
        missing_pkgs+=(playerctl)
    fi
    if ! command -v brightnessctl &>/dev/null && command -v pacman &>/dev/null; then
        missing_pkgs+=(brightnessctl)
    fi
    if ! command -v wl-copy &>/dev/null && command -v pacman &>/dev/null; then
        missing_pkgs+=(wl-clipboard)
    fi
    if ! command -v qrencode &>/dev/null && command -v pacman &>/dev/null; then
        missing_pkgs+=(qrencode)
    fi
    if ! command -v rsvg-convert &>/dev/null && command -v pacman &>/dev/null; then
        missing_pkgs+=(librsvg)
    fi

    # Deduplicate
    local unique_pkgs=()
    if [ ${#missing_pkgs[@]} -gt 0 ]; then
        for p in "${missing_pkgs[@]}"; do
            [[ " ${unique_pkgs[*]:-} " =~ " ${p} " ]] || unique_pkgs+=("$p")
        done
    fi

    if [ ${#unique_pkgs[@]} -gt 0 ]; then
        warn "Recommended system packages missing: ${unique_pkgs[*]}"
        if command -v pacman &>/dev/null; then
            if prompt_yes_no "Install missing packages with pacman? [Y/n]"; then
                sudo pacman -S --needed --noconfirm "${unique_pkgs[@]}"
            else
                warn "Continuing without installing packages. Some features may not work."
            fi
        fi
    fi

    success "System environment verified."
}

check_dependencies

# Setup build directory
TMP_DIR=""
cleanup() {
    if [[ -n "${TMP_DIR}" && -d "${TMP_DIR}" ]]; then
        rm -rf "${TMP_DIR}"
    fi
}
trap cleanup EXIT

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd || true)"

# Check if running from cloned repository or piped from curl
if [[ -f "${SCRIPT_DIR}/daemon/Cargo.toml" && -f "${SCRIPT_DIR}/bin/hyprconnect-tray" ]]; then
    SOURCE_DIR="${SCRIPT_DIR}"
    info "Installing from local repository directory: ${SOURCE_DIR}"
else
    info "Downloading HyprConnect source repository..."
    TMP_DIR="$(mktemp -d /tmp/hyprconnect-install-XXXXXX)"
    git clone --depth 1 "${REPO_URL}" "${TMP_DIR}"
    SOURCE_DIR="${TMP_DIR}"
fi

# 1. Build Rust daemon
info "Compiling HyprConnect daemon (release build)..."
cd "${SOURCE_DIR}/daemon"
cargo build --release --locked || cargo build --release
success "Daemon compiled successfully."

# 2. Install binaries
info "Installing executables to ${INSTALL_BIN_DIR}..."
mkdir -p "${INSTALL_BIN_DIR}"
cp "${SOURCE_DIR}/daemon/target/release/hyprconnect" "${INSTALL_BIN_DIR}/hyprconnect"
cp "${SOURCE_DIR}/bin/hyprconnect-tray" "${INSTALL_BIN_DIR}/hyprconnect-tray"
chmod +x "${INSTALL_BIN_DIR}/hyprconnect" "${INSTALL_BIN_DIR}/hyprconnect-tray"
success "Binaries installed."

# 3. Install icons
info "Installing icon themes and desktop assets..."
mkdir -p "${INSTALL_ICON_DIR}"
mkdir -p "${INSTALL_SHARE_DIR}/icons/hicolor/scalable/apps"
mkdir -p "${INSTALL_SHARE_DIR}/pixmaps"

cp -r "${SOURCE_DIR}/icons/"* "${INSTALL_ICON_DIR}/"

if [[ -f "${SOURCE_DIR}/icons/hyprconnect.svg" ]]; then
    cp "${SOURCE_DIR}/icons/hyprconnect.svg" "${INSTALL_SHARE_DIR}/icons/hicolor/scalable/apps/hyprconnect.svg"
fi
if [[ -f "${SOURCE_DIR}/icons/hyprconnect-tray-white.svg" ]]; then
    cp "${SOURCE_DIR}/icons/hyprconnect-tray-white.svg" "${INSTALL_SHARE_DIR}/icons/hicolor/scalable/apps/hyprconnect-tray-white.svg"
fi

for s in 16 22 24 32 48 64 128; do
    if [[ -f "${SOURCE_DIR}/icons/hyprconnect-${s}.png" ]]; then
        mkdir -p "${INSTALL_SHARE_DIR}/icons/hicolor/${s}x${s}/apps"
        cp "${SOURCE_DIR}/icons/hyprconnect-${s}.png" "${INSTALL_SHARE_DIR}/icons/hicolor/${s}x${s}/apps/hyprconnect.png"
    fi
    if [[ -f "${SOURCE_DIR}/icons/hyprconnect-tray-${s}.png" ]]; then
        mkdir -p "${INSTALL_SHARE_DIR}/icons/hicolor/${s}x${s}/apps"
        cp "${SOURCE_DIR}/icons/hyprconnect-tray-${s}.png" "${INSTALL_SHARE_DIR}/icons/hicolor/${s}x${s}/apps/hyprconnect-tray-white.png"
    fi
done

if [[ -f "${SOURCE_DIR}/icons/hyprconnect.png" ]]; then
    cp "${SOURCE_DIR}/icons/hyprconnect.png" "${INSTALL_SHARE_DIR}/pixmaps/hyprconnect.png"
fi
if [[ -f "${SOURCE_DIR}/icons/hyprconnect-tray.png" ]]; then
    cp "${SOURCE_DIR}/icons/hyprconnect-tray.png" "${INSTALL_SHARE_DIR}/pixmaps/hyprconnect-tray-white.png"
fi

gtk-update-icon-cache -f -t "${INSTALL_SHARE_DIR}/icons/hicolor" 2>/dev/null || true
success "Icons installed."

# 4. Install Desktop File
info "Installing .desktop launcher..."
mkdir -p "${INSTALL_APP_DIR}"
if [[ -f "${SOURCE_DIR}/linux-extras/hyprconnect.desktop" ]]; then
    cp "${SOURCE_DIR}/linux-extras/hyprconnect.desktop" "${INSTALL_APP_DIR}/hyprconnect.desktop"
    update-desktop-database "${INSTALL_APP_DIR}" 2>/dev/null || true
    success "Desktop launcher installed."
fi

# 5. Install systemd user service
info "Installing systemd user service..."
mkdir -p "${INSTALL_SERVICE_DIR}"
if [[ -f "${SOURCE_DIR}/linux-extras/hyprconnect.service" ]]; then
    cp "${SOURCE_DIR}/linux-extras/hyprconnect.service" "${INSTALL_SERVICE_DIR}/hyprconnect.service"
    systemctl --user daemon-reload 2>/dev/null || true
    success "Systemd user service installed."
fi

# 6. Configure uinput permissions
info "Checking kernel /dev/uinput permissions..."
NEED_UDEV=0
if [[ ! -w /dev/uinput ]]; then
    NEED_UDEV=1
fi

if [[ "${NEED_UDEV}" -eq 1 ]]; then
    warn "User '${USER}' requires write permissions for /dev/uinput (used for virtual trackpad & keyboard injection)."
    if prompt_yes_no "Apply udev rule and add '${USER}' to group 'input'? (Requires sudo) [Y/n]"; then
        sudo usermod -aG input "${USER}" || true
        if [[ -f "${SOURCE_DIR}/linux-extras/99-hyprconnect-uinput.rules" ]]; then
            sudo cp "${SOURCE_DIR}/linux-extras/99-hyprconnect-uinput.rules" /etc/udev/rules.d/99-hyprconnect-uinput.rules
            sudo udevadm control --reload-rules && sudo udevadm trigger || true
            success "Udev rules configured."
        fi
    fi
else
    success "/dev/uinput permissions are already configured."
fi

# 7. PATH verification
if [[ ":$PATH:" != *":${INSTALL_BIN_DIR}:"* ]]; then
    warn "${INSTALL_BIN_DIR} is not in your current PATH."
    echo -e "  Add this to your ${BOLD}~/.bashrc${RESET} or ${BOLD}~/.zshrc${RESET}:"
    echo -e "    ${CYAN}export PATH=\"\$HOME/.local/bin:\$PATH\"${RESET}\n"
fi

echo -e "\n${GREEN}${BOLD}================================================================${RESET}"
echo -e "${GREEN}${BOLD}       HyprConnect v1.0 Installed Successfully!         ${RESET}"
echo -e "${GREEN}${BOLD}================================================================${RESET}\n"

echo -e "${BOLD}🚀 How to run:${RESET}"
echo -e "  • Start the background daemon & system tray now:"
echo -e "    ${CYAN}hyprconnect daemon${RESET}\n"
echo -e "  • Pair your phone:"
echo -e "    ${CYAN}hyprconnect pair${RESET} (or click 'Pair New Device' in the tray)\n"
echo -e "  • Auto-start with Hyprland:"
echo -e "    Add to ${BOLD}~/.config/hypr/hyprland.conf${RESET}:"
echo -e "    ${MAGENTA}exec-once = hyprconnect daemon${RESET}\n"
echo -e "  • Or enable via systemd:"
echo -e "    ${CYAN}systemctl --user enable --now hyprconnect.service${RESET}\n"

