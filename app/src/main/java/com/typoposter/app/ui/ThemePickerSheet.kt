package com.typoposter.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typoposter.app.theme.PosterTheme
import com.typoposter.app.theme.ThemeEngine

// ─────────────────────────────────────────────────────────────
//  Theme Picker — Modal Bottom Sheet
// ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemePickerSheet(
    currentThemeIndex: Int,
    activeTheme: PosterTheme,
    onThemeSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = activeTheme.surfaceVariant,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(activeTheme.textSecondary.copy(alpha = 0.4f))
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .navigationBarsPadding(),
        ) {
            // ── Header ──────────────────────────────────
            Text(
                text = "Themes",
                color = activeTheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp,
                modifier = Modifier.padding(bottom = 20.dp),
            )

            // ── Theme Cards ─────────────────────────────
            ThemeEngine.allThemes.forEachIndexed { index, theme ->
                ThemeCard(
                    theme = theme,
                    isSelected = index == currentThemeIndex,
                    activeTheme = activeTheme,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onThemeSelected(index)
                    },
                )
                if (index < ThemeEngine.allThemes.lastIndex) {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Individual Theme Preview Card
// ─────────────────────────────────────────────────────────────
@Composable
private fun ThemeCard(
    theme: PosterTheme,
    isSelected: Boolean,
    activeTheme: PosterTheme,
    onClick: () -> Unit,
) {
    val borderColor = animateColorAsState(
        targetValue = if (isSelected) activeTheme.accent else activeTheme.textSecondary.copy(alpha = 0.15f),
        animationSpec = tween(180),
        label = "border",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(theme.background)
            .border(
                width = 2.dp,
                color = borderColor.value,
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Color swatch column
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ColorDot(theme.textPrimary)
                ColorDot(theme.accent)
                ColorDot(theme.textSecondary)
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Theme name and preview text
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = theme.name,
                color = theme.textPrimary,
                fontFamily = theme.headerFont,
                fontWeight = theme.headerWeight,
                fontSize = 15.sp,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Aa Bb Cc 123",
                color = theme.textSecondary,
                fontFamily = theme.bodyFont,
                fontWeight = theme.bodyWeight,
                fontSize = 12.sp,
            )
        }

        // Selection indicator
        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(activeTheme.accent),
            )
        }
    }
}

@Composable
private fun ColorDot(color: Color) {
    Box(
        modifier = Modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(color),
    )
}
