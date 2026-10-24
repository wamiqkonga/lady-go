package com.ladygo.driver.testing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Renders [content] as if the system font scale were [fontScale] (the phone width comes from the test's `@Config(qualifiers = "w360dp...")`). */
@Composable
fun WithFontScale(
    fontScale: Float,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), content = content)
}

fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
    val results = mutableListOf<TextLayoutResult>()
    fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
    return results.first()
}

fun SemanticsNodeInteraction.assertSingleLine() = assertEquals(1, textLayout().lineCount)

/** Every line break falls on whitespace: no word is split across lines. */
fun SemanticsNodeInteraction.assertNoMidWordBreak() {
    val layout = textLayout()
    val text = layout.layoutInput.text.text
    for (line in 0 until layout.lineCount - 1) {
        val end = layout.getLineEnd(line)
        assertTrue("line ${line + 1} of '$text' breaks inside a word", text[end - 1].isWhitespace() || text[end].isWhitespace())
    }
}

/** [this] sits fully above [other]: a stacked label/value pair. */
fun SemanticsNodeInteraction.assertStackedAbove(other: SemanticsNodeInteraction) {
    val a = getBoundsInRoot()
    val b = other.getBoundsInRoot()
    assertTrue("expected stacked, got $a and $b", a.bottom <= b.top + 0.5.dp)
}

/** [this] and [other] share a row: the value starts to the right of the label, on the same band. */
fun SemanticsNodeInteraction.assertSideBySideWith(other: SemanticsNodeInteraction) {
    val a = getBoundsInRoot()
    val b = other.getBoundsInRoot()
    assertTrue("expected two columns, got $a and $b", b.left > a.left && b.top < a.bottom)
}
