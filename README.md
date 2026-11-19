# 🖋️ Typo Poster

A minimalist, distraction-free typographic note-taking Android app with live, responsive home screen widgets built with **Jetpack Compose** & **Jetpack Glance**.

Designed around negative space, curated editorial typography, and instant home screen glanceability.

---

## ✨ Features

- **Distraction-Free Editor:** Clean typography-first canvas with dynamic auto-scaling text sizing (short notes render as bold posters; long notes scale gracefully for comfortable reading).
- **Curated Theme Engine:**
  - ⬛ **The Executive:** Deep matte black (`#0A0A0A`) with crisp white typography and electric neon green accents (Inter & Roboto Mono).
  - 📜 **The Editorial:** Warm off-white (`#FAF7F2`) with charcoal text and editorial red accents (Playfair Display & Lora).
  - 📐 **The Blueprint:** Deep navy (`#0D1B2A`) with crisp white typography and cyan accents (Montserrat).
- **Live Home Screen Widgets (Jetpack Glance):**
  - **Zero-lag real-time updates:** Updates the moment you type or switch themes without needing to tap the widget.
  - **4 responsive breakpoints:** Small (180×80dp), Medium (250×110dp), Large (320×200dp), and Extra Large (400×300dp).
  - **Direct Launch:** Tapping a widget instantly launches the editor with the keyboard ready.
- **Multiple Notes & Multiple Widgets:**
  - Create multiple notes for different purposes (*Work*, *Ideas*, *Focus*, *Shopping*).
  - Each note retains its own independent theme.
  - Drag widgets and assign them to specific notes, or use the in-app **`+ WIDGET`** button to pin them with 1 tap.
- **Rich Typography Formatting:**
  - **Bold (`**text**`)** & *Italics (`*text*`)*.
  - **5 Aesthetic Highlighter Colors:**
    - 🟡 **Acid Lime:** Vibrant neon highlighter
    - 🟠 **Sunset Coral:** Warm punchy peach
    - 🔵 **Cyber Sky:** Crisp electric sky mint
    - 🟣 **Deep Plum:** Rich velvety dark berry / wine
    - 🟢 **Forest Pine:** Moody deep emerald pine
  - True inline background text highlighting (using native `BackgroundColorSpan` in RemoteViews) without bullet symbols or heading clutter.
- **📷 Camera Scan & On-Device AI OCR:**
  - **Instant Camera & Gallery Capture:** Snap a photo of physical books, documents, signs, posters, or import any screenshot/image from your gallery.
  - **On-Device ML Kit OCR:** Fully offline, fast, privacy-preserving on-device text recognition.
  - **Typographic Cleaning & Aesthetic Preview:** Broken hyphenations across line breaks are re-joined, clean paragraph spacing is restored, and the text is rendered immediately in your chosen theme with auto-scaled typography.
  - **1-Tap Direct-to-Widget:** Convert any physical document into a live home screen widget instantly with the **`CREATE & ADD TO WIDGET`** button.
  - **Widget Quick-Scan Button:** Tap the camera icon directly in the widget's header from your home screen to instantly open the scanner.

---

## 🛠️ Architecture & Tech Stack

- **UI:** Jetpack Compose (Material 3)
- **Widgets:** Jetpack Glance AppWidget (Material 3)
- **OCR Engine:** Google ML Kit Text Recognition (On-device offline ML)
- **State Management:** Unidirectional Data Flow (UDF) via Kotlin `StateFlow` and `AndroidViewModel`
- **Persistence:** Jetpack DataStore Preferences (Zero Room / SQLite migration overhead)
- **Fonts:** Runtime Google Fonts Provider (`androidx.compose.ui.text.googlefonts`)
- **Min SDK:** Android 8.0 (API 26) | **Target SDK:** Android 14 (API 34)

---

## 🚀 Building & Running

### Option 1: Android Studio
1. Clone the repository:
   ```bash
   git clone https://github.com/hifzur-r2k/TypoPoster.git
   ```
2. Open the project in Android Studio (Iguana / Jellyfish / Ladybug or newer).
3. Let Gradle sync and press **Run**.

### Option 2: Command Line
```bash
./gradlew assembleDebug
# Install on connected device/emulator:
./gradlew installDebug
```

---

## 📄 License

MIT License. Designed with care for typography lovers.
