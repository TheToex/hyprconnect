# HyprConnect

یه جایگزین مستقل از صفر شبیه KDE Connect برای Hyprland. پروتکل، دیمن و اپ اندروید همه مال خودشونه (با KDE Connect سازگار نیست).

```
hyprconnect/
├── daemon/          Rust: دیمن + CLI (یک باینری: `hyprconnect`)
├── android/         Kotlin + Jetpack Compose (باید توی Android Studio بیلد بشه)
├── linux-extras/    قانون udev و سرویس systemd
└── bin/             باینری آماده x86_64 (ساخته‌شده روی Ubuntu 24.04)
```

## امکانات

| قابلیت | وضعیت |
|---|---|
| جفت‌سازی با QR، TLS با پین‌کردن گواهی، توکن یک‌بارمصرف | تست‌شده (دیمن) |
| انتقال فایل دو طرفه (جریانی، تا ۸GB) | سمت دیمن تست‌شده |
| تاچ‌پد (حرکت، کلیک، راست‌کلیک، اسکرول) + کیبورد (متن یونیکد/فارسی با `wtype` + کلیدهای خاص) | پیاده‌سازی شده |
| ریموت ارائه: قبلی/بعدی، F5، Esc، صفحه سیاه، کلیدهای صدا، تایمر | پیاده‌سازی شده |
| پوینتر با ژیروسکوپ (نشانگر ماوس با کج کردن گوشی حرکت می‌کنه) | پیاده‌سازی شده |
| Hyprland: لیست و سوییچ ورک‌اسپیس، لیست و فوکوس پنجره‌ها (event socket زنده) | پیاده‌سازی شده |
| مدیا (playerctl)، صدا (wpctl)، روشنایی (brightnessctl) | پیاده‌سازی شده |
| کامندهای از پیش تعریف‌شده (`config.json`) | پیاده‌سازی شده |
| کلیپ‌بورد دو طرفه (wl-clipboard) | پیاده‌سازی شده |
| نوتیفیکیشن‌های گوشی روی دسکتاپ | پیاده‌سازی شده |
| پیامک: ارسال از PC و فوروارد پیامک دریافتی به دسکتاپ | پیاده‌سازی شده |
| باتری گوشی، پیدا کردن گوشی (ring) | پیاده‌سازی شده |
| کشف خودکار با mDNS وقتی IP عوض شد | پیاده‌سازی شده |

## نصب سمت لینوکس

```bash
sudo pacman -S wtype playerctl brightnessctl wl-clipboard libnotify
# اختیاری: grim (اسکرین‌شات)، hyprlock (قفل)

sudo install -Dm644 linux-extras/99-hyprconnect-uinput.rules /etc/udev/rules.d/
sudo usermod -aG input $USER          # بعدش یک بار logout/login
sudo udevadm control --reload && sudo modprobe uinput
echo uinput | sudo tee /etc/modules-load.d/uinput.conf

install -Dm755 bin/hyprconnect ~/.local/bin/hyprconnect
# یا از سورس:  cd daemon && cargo build --release
```

توی `~/.config/hypr/hyprland.conf`:

```
exec-once = hyprconnect daemon
```
(دیمن همون‌جا محیط Wayland/Hyprland رو ارث می‌بره. نسخه‌ی systemd: `linux-extras/hyprconnect.service`.)

پورت `1716/tcp` رو برای شبکه‌ی محلی توی فایروال باز کن.

## استفاده

```bash
hyprconnect pair            # QR نشون می‌ده، توی اپ اسکن کن (۲ دقیقه اعتبار)
hyprconnect devices
hyprconnect send file.pdf
hyprconnect ring
hyprconnect sms +98912xxxxxxx "سلام"
hyprconnect clip            # کلیپ‌بورد PC -> گوشی
hyprconnect unpair <id>
```

کامندهای قابل اجرا از گوشی: `~/.config/hyprconnect/config.json` → `commands`. گوشی فقط **اسم** کامند رو می‌فرسته، نه رشته‌ی شل.

## بیلد اپ اندروید

1. پوشه‌ی `android/` رو توی Android Studio باز کن و Sync بزن (Gradle و SDK رو خودش می‌گیره).
2. Run روی گوشی یا Build → Build APK(s).
3. minSdk 29 (اندروید ۱۰+). اسکنر QR به Google Play Services نیاز داره؛ بدونش لینک `hyprconnect://…` رو دستی paste کن.
4. دسترسی نوتیفیکیشن و پیامک رو از تب More فعال کن.
5. برای پایدار موندن اتصال، بهینه‌سازی باتری رو برای اپ خاموش کن.

## مدل امنیتی

- دیمن یه گواهی self-signed می‌سازه؛ QR فینگرپرینت SHA-256 اون رو می‌رسونه و گوشی فقط همون گواهی رو قبول می‌کنه (ضد MITM).
- توکن جفت‌سازی ۱۶ بایت تصادفیِ یک‌بارمصرف با ۲ دقیقه اعتبار. بعدش هر گوشی یه secret ۳۲ بایتی می‌گیره (روی PC فقط هش SHA-256 ذخیره می‌شه).
- نام فایل‌های دریافتی پاکسازی می‌شن (path traversal تست شد)، خطوط پروتکل سقف اندازه دارن، احراز هویت ناموفق ۱ ثانیه تأخیر می‌خوره.
- کلید خصوصی و devices.json با دسترسی 0600 ذخیره می‌شن.
- گوشی گم شد؟ `hyprconnect unpair <id>`.

## محدودیت‌های نسخه‌ی ۰.۱

- **نقطه‌ی لیزری روی صفحه** نیست: پوینتر ژیروسکوپی خودِ نشانگر ماوس رو حرکت می‌ده. overlay واقعی با `wlr-layer-shell` کار بعدیه.
- درگ با ماوس (نگه‌داشتن کلیک) توی UI نیست؛ پروتکل `mouse.button` رو داره.
- پیامک: ارسال و دریافت هست، لیست مکالمات و چت نیست. دسترسی SMS برای Google Play محدودیت داره (برای APK شخصی مشکلی نیست).
- نوتیفیکیشن‌ها یک‌طرفه‌ان (گوشی → PC).
- اندروید ۱۰+ نوشتن کلیپ‌بورد از پس‌زمینه رو محدود می‌کنه؛ «PC → phone» وقتی اپ جلوئه کار می‌کنه.
- سمت اندروید هنوز روی گوشی واقعی اجرا نشده.
