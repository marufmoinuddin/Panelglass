package com.smnexstudio.panelglass.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smnexstudio.panelglass.core.model.GlossaryEntry
import com.smnexstudio.panelglass.core.model.Pronoun
import com.smnexstudio.panelglass.core.model.TranslationNotes

/** "He / him" and so on, in the app's language. */
@Composable
fun Pronoun?.label(): String = stringResource(
    when (this) {
        null -> R.string.pronoun_none
        Pronoun.HE -> R.string.pronoun_he
        Pronoun.SHE -> R.string.pronoun_she
        Pronoun.THEY -> R.string.pronoun_they
    },
)

/**
 * One series' names and terms (each maybe with a character's pronoun) and the user's notes for it, as the engines
 * get them. [scope] names what the series is (a site path). The notes are handed back once, on dismiss.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesMemorySheet(
    scope: String,
    terms: List<GlossaryEntry>,
    notes: String,
    onSaveTerm: (GlossaryEntry) -> Unit,
    onDeleteTerm: (GlossaryEntry) -> Unit,
    onNotes: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(scope, notes) { mutableStateOf(notes) }
    var editing by remember { mutableStateOf<GlossaryEntry?>(null) }
    val close = { onNotes(draft); onDismiss() }

    ModalBottomSheet(onDismissRequest = close, containerColor = Tokens.Card, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.memory_title), fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 20.sp, color = Tokens.Ink)
            Text(
                stringResource(R.string.memory_scope, scope), fontFamily = Jakarta, fontSize = 12.5.sp, color = Tokens.InkSoft,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )

            SectionLabel(stringResource(R.string.memory_terms), Modifier.padding(top = 6.dp, bottom = 4.dp), inset = 0.dp)
            if (terms.isEmpty()) {
                Text(stringResource(R.string.memory_empty), style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = Tokens.InkSoft)
                Spacer(Modifier.height(8.dp))
            } else TokenCard {
                terms.forEachIndexed { i, t ->
                    if (i > 0) CardDivider()
                    TermRow(t, onEdit = { editing = t }, onDelete = { onDeleteTerm(t) })
                }
            }
            Spacer(Modifier.height(8.dp))
            DashedRow(stringResource(R.string.memory_add_term), onClick = { editing = GlossaryEntry(seriesKey = "", source = "", target = "") })

            SectionLabel(stringResource(R.string.memory_notes_label), Modifier.padding(top = 6.dp, bottom = 4.dp), inset = 0.dp)
            OutlinedTextField(
                value = draft, onValueChange = { draft = it.take(TranslationNotes.MAX_CHARS) },
                placeholder = { Text(stringResource(R.string.memory_notes_hint)) },
                minLines = 3, maxLines = 6,
                supportingText = { Text("${draft.length} / ${TranslationNotes.MAX_CHARS}") },
                shape = RoundedCornerShape(14.dp), colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.memory_engine_note), style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = Tokens.InkSoft, modifier = Modifier.padding(top = 4.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                PrimaryPill(stringResource(R.string.action_save).uppercase(), letterSpaced = true, onClick = close)
            }
        }
    }

    editing?.let { e ->
        TermDialog(
            e,
            onDismiss = { editing = null },
            onSave = { onSaveTerm(it); editing = null },
            onDelete = if (e.id != 0L) ({ onDeleteTerm(e); editing = null }) else null,
        )
    }
}

@Composable
private fun TermRow(t: GlossaryEntry, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(start = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(t.target, fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 15.sp, color = Tokens.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.source, fontFamily = Jakarta, fontSize = 12.5.sp, color = Tokens.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (t.pronoun != null) Text(
            t.pronoun.label(), fontFamily = Jakarta, fontWeight = FontWeight.W600, fontSize = 12.sp, color = Tokens.SkyDeep,
            modifier = Modifier.padding(start = 8.dp),
        )
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete), tint = Tokens.InkFaint, modifier = Modifier.size(20.dp))
        }
    }
}

/** Adds or edits one name or term: the original as on the page, its translation, and a pronoun for a character. */
@Composable
private fun TermDialog(entry: GlossaryEntry, onDismiss: () -> Unit, onSave: (GlossaryEntry) -> Unit, onDelete: (() -> Unit)?) {
    var source by remember(entry) { mutableStateOf(entry.source) }
    var target by remember(entry) { mutableStateOf(entry.target) }
    var pronoun by remember(entry) { mutableStateOf(entry.pronoun) }
    StickerDialog(onDismiss) {
        Text(stringResource(R.string.memory_term_title), fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 20.sp, color = Tokens.Ink)
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = source, onValueChange = { source = it }, label = { Text(stringResource(R.string.memory_term_source)) },
            singleLine = true, shape = RoundedCornerShape(14.dp), colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = target, onValueChange = { target = it }, label = { Text(stringResource(R.string.memory_term_target)) },
            singleLine = true, shape = RoundedCornerShape(14.dp), colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
        )
        SectionLabel(stringResource(R.string.memory_term_pronoun), Modifier.padding(top = 6.dp, bottom = 4.dp), inset = 0.dp)
        Row {
            (listOf<Pronoun?>(null) + Pronoun.entries).forEach { p -> Chip(p.label(), pronoun == p) { pronoun = p } }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (onDelete != null) {
                TextAction(stringResource(R.string.action_delete), Tokens.Error, onDelete)
                Spacer(Modifier.weight(1f))
            }
            TextAction(stringResource(R.string.action_cancel), Tokens.InkSoft, onDismiss)
            Spacer(Modifier.width(8.dp))
            PrimaryPill(
                stringResource(R.string.action_save).uppercase(), enabled = source.isNotBlank() && target.isNotBlank(), letterSpaced = true,
                onClick = { onSave(entry.copy(source = source.trim(), target = target.trim(), pronoun = pronoun)) },
            )
        }
    }
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        text, fontFamily = Jakarta, fontWeight = FontWeight.W600, fontSize = 13.sp, color = if (on) Tokens.Card else Tokens.Ink, maxLines = 1,
        modifier = Modifier.padding(end = 6.dp).clip(shape)
            .background(if (on) Tokens.Ink else Tokens.Card)
            .then(if (on) Modifier else Modifier.border(1.dp, Tokens.Border, shape))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Tokens.Ink, unfocusedBorderColor = Tokens.Border, focusedTextColor = Tokens.Ink, unfocusedTextColor = Tokens.Ink,
    cursorColor = Tokens.Ink, focusedLabelColor = Tokens.InkSoft, unfocusedLabelColor = Tokens.InkFaint,
    focusedPlaceholderColor = Tokens.InkFaint, unfocusedPlaceholderColor = Tokens.InkFaint,
)
