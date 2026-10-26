# DeskPad — Browser desktop di HP, tampilan ala Game Boy

Aplikasi Android native (Kotlin, tanpa library tambahan). Layar atas menampilkan
**browser desktop sungguhan** (viewport 1280×720, User-Agent Chrome Windows) dalam jendela
landscape 16:9. Di bawahnya ada dua mode kontrol:

| Mode | Fungsi |
|------|--------|
| **KEYBOARD** | Keyboard PC lengkap: Esc, F1–F12, angka, QWERTY, Tab, Caps, Enter, Shift, Ctrl, Alt, Win, panah. Lapisan **Fn**: Home/End/PgUp/PgDn/Ins/Del + Copy/Cut/Paste/All. Shift/Ctrl/Alt/Win: ketuk = sekali pakai, tahan lama = terkunci. Tahan tombol = auto-repeat. |
| **MOUSE** | Trackpad + kursor virtual. 1 jari = gerak, ketuk = klik kiri, ketuk 2 jari = klik kanan, geser 2 jari = scroll, ketuk lalu tahan & geser = drag. Strip **SCROLL**, **D-pad** (presisi), tombol **A** = klik kiri, **B** = klik kanan, **SELECT** = kunci drag, **START** = ganti kecepatan kursor. |

Semua input dikirim sebagai `KeyEvent` dan `MotionEvent` mouse asli (hover, tombol, roda scroll),
bukan JavaScript, jadi bekerja di game web, editor online, dsb.

Menu **⋮**: tab baru/daftar tab, ketik/tempel teks Unicode, resolusi layar virtual
(1024×576 – 1920×1080), kecepatan kursor & scroll, mode layar sentuh langsung,
keyboard sistem HP, hapus cookie.

## Cara membuat APK

### Cara 1 — GitHub (tanpa laptop, bisa dari HP)
1. Buat repository baru di GitHub, upload **seluruh isi folder ini** (termasuk folder `.github`).
2. Buka tab **Actions** → workflow **Build APK** → tunggu hijau (±3–5 menit).
3. Buka run tersebut → bagian **Artifacts** → unduh **DeskPad-APK** → ekstrak → pasang `app-debug.apk`.
   (Izinkan "install dari sumber tidak dikenal" di HP.)

### Cara 2 — Android Studio
Buka folder ini di Android Studio (Koala atau lebih baru) → **Build ▸ Build APK(s)**.

### Cara 3 — Command line
Butuh JDK 17, Android SDK (platform 34), dan Gradle 8.7+:
```
gradle assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`

## Catatan
- Login Google di dalam WebView sering ditolak oleh Google (kebijakan mereka), situs lain umumnya normal.
- Resolusi 1920×1080 berat di HP kelas menengah; 1280×720 adalah default yang seimbang.
- Kecepatan scroll mouse bisa disesuaikan di menu ⋮ bila terasa terlalu cepat/lambat.
- Aplikasi dikunci ke mode portrait; layar browser-nya yang landscape.
