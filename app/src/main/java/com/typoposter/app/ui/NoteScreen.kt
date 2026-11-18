package com.typoposter.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typoposter.app.data.Note
import com.typoposter.app.theme.FormatType
import com.typoposter.app.theme.PosterTheme
import com.typoposter.app.theme.RichTextVisualTransformation
import com.typoposter.app.theme.TextFormatHelper

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteScreen(
    uiState: NoteUiState,
    onTextChanged: (String) -> Unit,
    onNoteSelected: (String) -> Unit,
    onCreateNewNote: () -> Unit,
    onDeleteNote: () -> Unit,
    onRenameNote: (String) -> Unit,
    onPinWidgetClicked: () -> Unit,
    onThemeButtonClicked: () -> Unit,
    onThemeSelected: (Int) -> Unit,
    onThemeSheetDismissed: () -> Unit,
    onRenameDialogDismissed: () -> Unit,
    onShowRenameDialog: () -> Unit,
    launchWithKeyboard: Boolean = false,
) {
    val theme = uiState.theme
    val activeNote = uiState.activeNote
    val focusRequester = remember { FocusRequester() }

    // Maintain selection state so bold/italic/highlight operations can target the selection
    var textFieldValue by remember(activeNote.id) {
        mutableStateOf(TextFieldValue(activeNote.content, TextRange(activeNote.content.length)))
    }

    LaunchedEffect(activeNote.content) {
        if (activeNote.content != textFieldValue.text) {
            textFieldValue = textFieldValue.copy(text = activeNote.content)
        }
    }

    val backgroundColor = animateColorAsState(
        targetValue = theme.background,
        animationSpec = tween(400),
        label = "bg",
    )
    val textColor = animateColorAsState(
        targetValue = theme.textPrimary,
        animationSpec = tween(400),
        label = "text",
    )
    val secondaryColor = animateColorAsState(
        targetValue = theme.textSecondary,
        animationSpec = tween(400),
        label = "secondary",
    )
    val accentColor = animateColorAsState(
        targetValue = theme.accent,
        animationSpec = tween(400),
        label = "accent",
    )

    LaunchedEffect(launchWithKeyboard) {
        if (launchWithKeyboard) {
            focusRequester.requestFocus()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor.value)
            .systemBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // ── Top Bar ─────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // App Logo
                Text(
                    text = "typo",
                    color = secondaryColor.value.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    fontFamily = theme.headerFont,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 4.sp,
                )

                // Actions: Add to Widget button + Theme button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Direct "Add to Widget" button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(accentColor.value.copy(alpha = 0.12f))
                            .clickable(onClick = onPinWidgetClicked)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "+ WIDGET",
                            color = accentColor.value,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.sp,
                        )
                    }

                    // Theme color button
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(accentColor.value.copy(alpha = 0.15f))
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = onThemeButtonClicked,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(accentColor.value),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Notes Switcher Tabs ─────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                uiState.notes.forEach { note ->
                    val isSelected = note.id == activeNote.id
                    val tabBg = if (isSelected) accentColor.value.copy(alpha = 0.15f)
                    else theme.surfaceVariant.copy(alpha = 0.5f)
                    val tabText = if (isSelected) textColor.value else secondaryColor.value

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(tabBg)
                            .combinedClickable(
                                onClick = { onNoteSelected(note.id) },
                                onLongClick = {
                                    if (isSelected) onShowRenameDialog()
                                }
                            )
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = note.title.uppercase(),
                            color = tabText,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            letterSpacing = 1.sp,
                        )
                    }
                }

                // Plus button to add note
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(theme.surfaceVariant.copy(alpha = 0.5f))
                        .clickable(onClick = onCreateNewNote),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "+",
                        color = secondaryColor.value,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Light,
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Accent Bar & Title ──────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(accentColor.value),
                )

                Text(
                    text = activeNote.title,
                    color = secondaryColor.value.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    fontFamily = theme.headerFont,
                    modifier = Modifier.clickable(onClick = onShowRenameDialog)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Text Editor ─────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (activeNote.content.isEmpty()) {
                    Text(
                        text = "What's on your mind today?",
                        color = secondaryColor.value.copy(alpha = 0.4f),
                        fontSize = 20.sp,
                        fontFamily = theme.bodyFont,
                        fontWeight = theme.bodyWeight,
                        lineHeight = 32.sp,
                    )
                }

                BasicTextField(
                    value = textFieldValue,
                    onValueChange = { newValue ->
                        textFieldValue = newValue
                        if (newValue.text != activeNote.content) {
                            onTextChanged(newValue.text)
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(focusRequester),
                    visualTransformation = remember(theme) {
                        RichTextVisualTransformation(theme)
                    },
                    textStyle = TextStyle(
                        color = textColor.value,
                        fontSize = autoScaleFontSize(activeNote.content.length),
                        fontFamily = theme.bodyFont,
                        fontWeight = if (activeNote.content.length <= 50) FontWeight.Medium
                        else theme.bodyWeight,
                        lineHeight = autoScaleLineHeight(activeNote.content.length),
                        letterSpacing = 0.3.sp,
                    ),
                    cursorBrush = SolidColor(accentColor.value),
                )
            }

            // ── Formatting Toolbar (Bold, Italic, 3 Highlights) ──
            FormattingToolbar(
                theme = theme,
                onFormat = { format ->
                    val updated = TextFormatHelper.toggleFormat(textFieldValue, format)
                    textFieldValue = updated
                    onTextChanged(updated.text)
                },
                onClearFormat = {
                    val updated = TextFormatHelper.clearFormatting(textFieldValue)
                    textFieldValue = updated
                    onTextChanged(updated.text)
                }
            )

            // ── Bottom status ───────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${activeNote.content.length} chars",
                    color = secondaryColor.value.copy(alpha = 0.35f),
                    fontSize = 11.sp,
                    fontFamily = theme.bodyFont,
                    letterSpacing = 1.sp,
                )

                Text(
                    text = theme.name.uppercase(),
                    color = secondaryColor.value.copy(alpha = 0.35f),
                    fontSize = 11.sp,
                    fontFamily = theme.headerFont,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.5.sp,
                )
            }
        }

        // ── Theme Picker Sheet ──────────────────────────
        if (uiState.isThemeSheetVisible) {
            ThemePickerSheet(
                currentThemeIndex = activeNote.themeIndex,
                activeTheme = theme,
                onThemeSelected = onThemeSelected,
                onDismiss = onThemeSheetDismissed,
            )
        }

        // ── Note Rename / Delete Dialog ─────────────────
        if (uiState.isRenameDialogVisible) {
            NoteActionDialog(
                currentTitle = activeNote.title,
                canDelete = uiState.notes.size > 1,
                theme = theme,
                onRename = onRenameNote,
                onDelete = onDeleteNote,
                onPinWidget = onPinWidgetClicked,
                onDismiss = onRenameDialogDismissed,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Typography Formatting Toolbar Component
// ─────────────────────────────────────────────────────────────

@Composable
private fun FormattingToolbar(
    theme: PosterTheme,
    onFormat: (FormatType) -> Unit,
    onClearFormat: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(theme.surfaceVariant.copy(alpha = 0.9f))
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Bold button
            Text(
                text = "B",
                color = theme.onSurface,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 15.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onFormat(FormatType.BOLD) }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )

            // Italic button
            Text(
                text = "I",
                color = theme.onSurface,
                fontStyle = FontStyle.Italic,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onFormat(FormatType.ITALIC) }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )

            // Divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(14.dp)
                    .background(theme.textSecondary.copy(alpha = 0.3f))
            )

            // Highlight 1: Acid Lime
            HighlightDot(
                color = TextFormatHelper.Highlights.SwatchLime,
                onClick = { onFormat(FormatType.HIGHLIGHT_1) }
            )

            // Highlight 2: Sunset Coral
            HighlightDot(
                color = TextFormatHelper.Highlights.SwatchCoral,
                onClick = { onFormat(FormatType.HIGHLIGHT_2) }
            )

            // Highlight 3: Cyber Sky
            HighlightDot(
                color = TextFormatHelper.Highlights.SwatchSky,
                onClick = { onFormat(FormatType.HIGHLIGHT_3) }
            )

            // Highlight 4: Deep Plum (Darker shade 1)
            HighlightDot(
                color = TextFormatHelper.Highlights.SwatchPlum,
                onClick = { onFormat(FormatType.HIGHLIGHT_4) }
            )

            // Highlight 5: Forest Pine (Darker shade 2)
            HighlightDot(
                color = TextFormatHelper.Highlights.SwatchPine,
                onClick = { onFormat(FormatType.HIGHLIGHT_5) }
            )

            // Divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(14.dp)
                    .background(theme.textSecondary.copy(alpha = 0.3f))
            )

            // Clear formatting
            Text(
                text = "✕",
                color = theme.textSecondary.copy(alpha = 0.6f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onClearFormat)
                    .padding(4.dp),
            )
        }
    }
}

@Composable
private fun HighlightDot(
    color: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick)
    )
}

@Composable
private fun NoteActionDialog(
    currentTitle: String,
    canDelete: Boolean,
    theme: PosterTheme,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onPinWidget: () -> Unit,
    onDismiss: () -> Unit,
) {
    var titleInput by remember { mutableStateOf(currentTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = theme.surfaceVariant,
        title = {
            Text(
                text = "Note Options",
                color = theme.onSurface,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "Rename Note:",
                    color = theme.textSecondary,
                    fontSize = 12.sp,
                )
                BasicTextField(
                    value = titleInput,
                    onValueChange = { titleInput = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = theme.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(theme.background)
                        .padding(12.dp),
                    cursorBrush = SolidColor(theme.accent),
                )

                // Quick action: Add to home screen widget
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(theme.accent.copy(alpha = 0.15f))
                        .clickable {
                            onPinWidget()
                            onDismiss()
                        }
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "📌 Add Widget to Home Screen",
                        color = theme.accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(titleInput) },
            ) {
                Text(text = "Save", color = theme.accent)
            }
        },
        dismissButton = {
            Row {
                if (canDelete) {
                    TextButton(
                        onClick = {
                            onDelete()
                            onDismiss()
                        },
                    ) {
                        Text(text = "Delete", color = Color(0xFFFF5252))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(text = "Cancel", color = theme.textSecondary)
                }
            }
        }
    )
}

private fun autoScaleFontSize(textLength: Int) = when {
    textLength <= 20  -> 28.sp
    textLength <= 50  -> 24.sp
    textLength <= 120 -> 20.sp
    textLength <= 250 -> 17.sp
    else              -> 15.sp
}

private fun autoScaleLineHeight(textLength: Int) = when {
    textLength <= 20  -> 40.sp
    textLength <= 50  -> 36.sp
    textLength <= 120 -> 30.sp
    textLength <= 250 -> 26.sp
    else              -> 23.sp
}
