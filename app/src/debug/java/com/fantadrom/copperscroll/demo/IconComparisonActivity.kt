package com.fantadrom.copperscroll.demo

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fantadrom.copperscroll.R
import com.fantadrom.copperscroll.ui.theme.CopperScrollTheme

/** Debug-only static comparison. No WorkspaceState, storage, file IO or Intent inputs. */
class IconComparisonActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        )
        window.isNavigationBarContrastEnforced = false
        setContent {
            // These two light backgrounds remain comparable even when the OS uses a dark theme.
            CopperScrollTheme(darkTheme = false, dynamicColor = false) { IconComparisonScreen() }
        }
    }
}

private data class Sample(val image: Int, val title: Int, val variant: Int)
private val Samples = listOf(
    Sample(R.drawable.demo_bank_simple, R.string.icon_demo_bank, R.string.icon_demo_simple),
    Sample(R.drawable.demo_bank_detailed, R.string.icon_demo_bank, R.string.icon_demo_detailed),
    Sample(R.drawable.demo_passport_simple, R.string.icon_demo_passport, R.string.icon_demo_simple),
    Sample(R.drawable.demo_passport_detailed, R.string.icon_demo_passport, R.string.icon_demo_detailed),
    Sample(R.drawable.demo_papers_simple, R.string.icon_demo_papers, R.string.icon_demo_simple),
    Sample(R.drawable.demo_papers_detailed, R.string.icon_demo_papers, R.string.icon_demo_detailed)
)
private val TextColor = Color(0xFF20252A)

@Composable
private fun IconComparisonScreen() {
    var blueBackground by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState() // Built-in saveable index/offset survive rotation.
    val rowColor = if (blueBackground) colorResource(R.color.screen_background) else Color.White
    Column(Modifier.fillMaxSize().background(Color.White).safeDrawingPadding()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Text(stringResource(R.string.icon_demo_title), color = TextColor, fontSize = 18.sp,
                fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
            Row(Modifier.fillMaxWidth().selectableGroup()) {
                BackgroundChoice(stringResource(R.string.icon_demo_white), !blueBackground,
                    { blueBackground = false }, Modifier.weight(1f))
                BackgroundChoice(stringResource(R.string.icon_demo_blue), blueBackground,
                    { blueBackground = true }, Modifier.weight(1f))
            }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f).background(rowColor)) {
            for (size in listOf(48, 54, 60)) {
                item(key = "section-$size") {
                    Text(stringResource(R.string.icon_demo_section, size, size + 12),
                        fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextColor,
                        modifier = Modifier.fillMaxWidth().background(Color(0xFFF3F5F7))
                            .padding(horizontal = 12.dp, vertical = 10.dp).semantics { heading() })
                }
                items(Samples, key = { "${size}-${it.image}" }) { sample ->
                    SampleRow(sample, size)
                    HorizontalDivider(color = Color(0xFFD0D7DE), thickness = 0.5.dp)
                }
            }
        }
    }
}

@Composable
private fun BackgroundChoice(label: String, selected: Boolean, onSelect: () -> Unit, modifier: Modifier) {
    Row(modifier.heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = onSelect)
        .padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 14.sp, color = TextColor)
    }
}

@Composable
private fun SampleRow(sample: Sample, size: Int) {
    val title = stringResource(sample.title)
    val variant = stringResource(sample.variant)
    val sizeDescription = stringResource(R.string.icon_demo_size_description, size)
    var menuExpanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = (size + 12).dp)
        .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
        .semantics(mergeDescendants = true) { stateDescription = sizeDescription },
        verticalAlignment = Alignment.CenterVertically) {
        // Row text announces the subject and variant; the image does not duplicate that announcement.
        Image(painterResource(sample.image), contentDescription = null,
            modifier = Modifier.size(size.dp), contentScale = ContentScale.Fit)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextColor, fontSize = 16.sp, lineHeight = 22.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Allow wrapping at large fontScale instead of clipping the date/variant vertically.
            Text(stringResource(R.string.icon_demo_secondary, variant), color = Color(0xFF4A5661),
                fontSize = 12.sp, lineHeight = 18.sp)
        }
        Box {
            IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_more),
                    contentDescription = stringResource(R.string.icon_demo_actions, title, variant, size),
                    tint = TextColor, modifier = Modifier.size(24.dp))
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.icon_demo_item)) },
                    onClick = { menuExpanded = false })
            }
        }
    }
}
