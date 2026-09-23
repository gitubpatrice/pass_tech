package com.filestech.pass_tech.ui.legal

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filestech.pass_tech.R
import com.filestech.pass_tech.core.legal.LegalDocument
import com.filestech.pass_tech.core.legal.LegalLibrary
import com.filestech.pass_tech.core.legal.LegalReading
import com.filestech.pass_tech.core.legal.Markdown
import com.filestech.pass_tech.ui.components.PtCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The privacy policy and the terms of use, read from the APK's own assets.
 *
 * **Nothing here reaches the network.** That is the point of shipping the documents rather than
 * linking to them: someone reading a privacy policy may well be doing so because they are wary of
 * what the app does online, and answering that question with a web page would be an odd reply. It
 * also means the text read is the one that came with the version installed, and not a page that has
 * moved on since.
 *
 * 3.0.0 carries all five languages the app speaks. A phone set to any other one is shown the English
 * document **and told so**, rather than being left to wonder whether its language was forgotten or
 * whether this is simply how it reads.
 */
@Composable
fun LegalScreen(document: LegalDocument, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val language = LocalConfiguration.current.locales[0].language
    val state by produceState<Load>(Load.Loading, document, language) {
        value = withContext(Dispatchers.IO) {
            LegalLibrary.read(context.assets, document, language)?.let(Load::Ready) ?: Load.Failed
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(title(document))) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 40.dp),
        ) {
            when (val current = state) {
                // An asset of a few kilobytes: a spinner here would be a flicker, not information.
                Load.Loading -> Unit
                Load.Failed -> item { Note(R.string.legal_unreadable) }
                is Load.Ready -> content(current.reading, language)
            }
        }
    }
}

private fun LazyListScope.content(reading: LegalReading, asked: String) {
    if (reading.language != asked) item { Note(R.string.legal_not_translated) }
    items(reading.blocks) { block -> BlockView(block) }
}

/** Loading, read, or unreadable — which on a sound build cannot happen, and is still answered. */
private sealed interface Load {
    data object Loading : Load

    data object Failed : Load

    data class Ready(val reading: LegalReading) : Load
}

@StringRes
private fun title(document: LegalDocument): Int = when (document) {
    LegalDocument.PRIVACY -> R.string.legal_privacy_title
    LegalDocument.TERMS -> R.string.legal_terms_title
}

@Composable
private fun Note(@StringRes text: Int) {
    PtCard(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        Text(
            text = stringResource(text),
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun BlockView(block: Markdown.Block) {
    when (block) {
        is Markdown.Block.Heading -> HeadingView(block)
        is Markdown.Block.Paragraph -> Text(
            text = rich(block.text),
            fontSize = 13.sp,
            lineHeight = 19.sp,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        is Markdown.Block.Item -> ItemView(block)
        is Markdown.Block.Quote -> QuoteView(block)
        Markdown.Block.Rule -> HorizontalDivider(Modifier.padding(vertical = 12.dp))
        is Markdown.Block.Table -> TableView(block)
    }
}

@Composable
private fun HeadingView(heading: Markdown.Block.Heading) {
    val top = heading.level <= 1
    Text(
        text = rich(heading.text),
        style = if (top) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = if (top) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = if (top) 8.dp else 22.dp, bottom = 4.dp),
    )
}

@Composable
private fun ItemView(item: Markdown.Block.Item) {
    Row(
        modifier = Modifier.padding(start = 4.dp, top = 3.dp, bottom = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (item.marker == DASH) BULLET else item.marker,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(text = rich(item.text), fontSize = 13.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun QuoteView(quote: Markdown.Block.Quote) {
    PtCard(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = rich(quote.text),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/**
 * A table on a phone.
 *
 * Drawn as columns, `| File | What it holds |` would be four words wide with a sideways scroll, which
 * on the two tables that matter here — the files on the phone, and the permissions measured on the
 * built APK — would hide exactly the facts a reader came to check. So each row becomes a card, and
 * each cell is labelled with its column: the header is repeated rather than shown once at the top,
 * because that is what makes a value readable on its own.
 *
 * A table with a header and no rows still shows its cells, unlabelled. Dropping them would be the one
 * outcome worse than an awkward layout.
 */
@Composable
private fun TableView(table: Markdown.Block.Table) {
    val rows = table.rows.ifEmpty { listOf(table.header) }
    val labelled = table.rows.isNotEmpty()
    Column(
        modifier = Modifier.padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rows.forEach { row ->
            PtCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    row.cells.forEachIndexed { index, cell ->
                        if (labelled) {
                            table.header.cells.getOrNull(index)?.let { label ->
                                Text(
                                    text = rich(label),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = if (index == 0) 0.dp else 6.dp),
                                )
                            }
                        }
                        Text(text = rich(cell), fontSize = 12.sp, lineHeight = 17.sp)
                    }
                }
            }
        }
    }
}

/** The two marks turned into type: bold is weight, code is a monospaced run on a tinted ground. */
@Composable
private fun rich(spans: List<Markdown.Span>): AnnotatedString {
    val ground = MaterialTheme.colorScheme.surfaceVariant
    return buildAnnotatedString {
        spans.forEach { span ->
            withStyle(
                SpanStyle(
                    fontWeight = if (span.bold) FontWeight.SemiBold else null,
                    fontFamily = if (span.code) FontFamily.Monospace else null,
                    background = if (span.code) ground else Color.Unspecified,
                ),
            ) {
                append(span.text)
            }
        }
    }
}

private const val DASH = "-"
private const val BULLET = "•"
