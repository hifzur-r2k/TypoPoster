package com.typoposter.app.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont

// ─────────────────────────────────────────────────────────────
//  Google Fonts Provider
// ─────────────────────────────────────────────────────────────
val fontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = emptyList() // Uses system trust on API 26+
)

// ─────────────────────────────────────────────────────────────
//  Font Families
// ─────────────────────────────────────────────────────────────

// Executive — strict, systematic
val InterFamily = FontFamily(
    Font(GoogleFont("Inter"), fontProvider, FontWeight.Light),
    Font(GoogleFont("Inter"), fontProvider, FontWeight.Normal),
    Font(GoogleFont("Inter"), fontProvider, FontWeight.Medium),
    Font(GoogleFont("Inter"), fontProvider, FontWeight.SemiBold),
    Font(GoogleFont("Inter"), fontProvider, FontWeight.Bold),
)

val RobotoMonoFamily = FontFamily(
    Font(GoogleFont("Roboto Mono"), fontProvider, FontWeight.Light),
    Font(GoogleFont("Roboto Mono"), fontProvider, FontWeight.Normal),
    Font(GoogleFont("Roboto Mono"), fontProvider, FontWeight.Medium),
    Font(GoogleFont("Roboto Mono"), fontProvider, FontWeight.Bold),
)

// Editorial — classic serif pairing
val PlayfairDisplayFamily = FontFamily(
    Font(GoogleFont("Playfair Display"), fontProvider, FontWeight.Normal),
    Font(GoogleFont("Playfair Display"), fontProvider, FontWeight.Medium),
    Font(GoogleFont("Playfair Display"), fontProvider, FontWeight.Bold),
    Font(GoogleFont("Playfair Display"), fontProvider, FontWeight.ExtraBold),
)

val LoraFamily = FontFamily(
    Font(GoogleFont("Lora"), fontProvider, FontWeight.Normal),
    Font(GoogleFont("Lora"), fontProvider, FontWeight.Medium),
    Font(GoogleFont("Lora"), fontProvider, FontWeight.SemiBold),
    Font(GoogleFont("Lora"), fontProvider, FontWeight.Bold),
)

// Blueprint — geometric sans-serif
val MontserratFamily = FontFamily(
    Font(GoogleFont("Montserrat"), fontProvider, FontWeight.Light),
    Font(GoogleFont("Montserrat"), fontProvider, FontWeight.Normal),
    Font(GoogleFont("Montserrat"), fontProvider, FontWeight.Medium),
    Font(GoogleFont("Montserrat"), fontProvider, FontWeight.SemiBold),
    Font(GoogleFont("Montserrat"), fontProvider, FontWeight.Bold),
)

// ─────────────────────────────────────────────────────────────
//  Poster Theme — single source of truth for each visual skin
// ─────────────────────────────────────────────────────────────
data class PosterTheme(
    val id: Int,
    val name: String,
    val background: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val headerFont: FontFamily,
    val bodyFont: FontFamily,
    val headerWeight: FontWeight = FontWeight.Bold,
    val bodyWeight: FontWeight = FontWeight.Normal,
    val surfaceVariant: Color,   // for UI elements (cards, sheets)
    val onSurface: Color,        // text on surfaceVariant
) {
    val isDark: Boolean get() = id != 1
}

object ThemeEngine {

    // Theme 1 ── The Executive
    val Executive = PosterTheme(
        id = 0,
        name = "The Executive",
        background = Color(0xFF0A0A0A),
        textPrimary = Color(0xFFF0F0F0),
        textSecondary = Color(0xFF8A8A8A),
        accent = Color(0xFF39FF14),         // electric green
        headerFont = InterFamily,
        bodyFont = RobotoMonoFamily,
        headerWeight = FontWeight.SemiBold,
        bodyWeight = FontWeight.Normal,
        surfaceVariant = Color(0xFF1A1A1A),
        onSurface = Color(0xFFE0E0E0),
    )

    // Theme 2 ── The Editorial
    val Editorial = PosterTheme(
        id = 1,
        name = "The Editorial",
        background = Color(0xFFFAF7F2),     // warm off-white
        textPrimary = Color(0xFF2C2C2C),     // charcoal
        textSecondary = Color(0xFF7A7A7A),
        accent = Color(0xFFB8392F),          // editorial red
        headerFont = PlayfairDisplayFamily,
        bodyFont = LoraFamily,
        headerWeight = FontWeight.Bold,
        bodyWeight = FontWeight.Normal,
        surfaceVariant = Color(0xFFF0EDE6),
        onSurface = Color(0xFF3A3A3A),
    )

    // Theme 3 ── The Blueprint
    val Blueprint = PosterTheme(
        id = 2,
        name = "The Blueprint",
        background = Color(0xFF0D1B2A),     // navy blue
        textPrimary = Color(0xFFFFFFFF),     // crisp white
        textSecondary = Color(0xFF8EAFC4),
        accent = Color(0xFF48CAE4),          // cyan accent
        headerFont = MontserratFamily,
        bodyFont = MontserratFamily,
        headerWeight = FontWeight.Bold,
        bodyWeight = FontWeight.Light,
        surfaceVariant = Color(0xFF1B2838),
        onSurface = Color(0xFFE0E8F0),
    )

    val allThemes = listOf(Executive, Editorial, Blueprint)

    fun getTheme(index: Int): PosterTheme =
        allThemes.getOrElse(index) { Executive }
}
