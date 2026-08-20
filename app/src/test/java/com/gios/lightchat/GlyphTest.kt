package com.gios.lightchat

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No key label may be a filled geometric shape.
 *
 * This exists because of a real report of a missing button: the dictation key was labelled `◉`
 * (U+25C9) and could not be found on the phone. A glyph the font does not have renders as nothing at
 * all, so an invisible key is indistinguishable from a feature that was never built.
 *
 * The rule is deliberately narrow, because a wider one would be false. This app *does* use `×`, `•`,
 * `‹`, `−`, `↑` and `⌫` as labels and has for many releases, so those are evidently fine — Latin-1,
 * general punctuation, arrows and technical symbols are covered. What had no precedent here was the
 * **Geometric Shapes** block (U+25A0–U+25FF), which is where `◉` and `■` live, and which is the one
 * thing that changed when a key went missing.
 *
 * A source scan rather than a rendering test, because rendering needs a device and the mistake is
 * plain in the source: a label is a literal string in a UI file.
 */
class GlyphTest {

    /** Geometric Shapes. Not a guess about the whole font — see the class comment. */
    private fun IntRange.holdsAny(text: String) = text.any { it.code in this }

    private val geometricShapes = 0x25A0..0x25FF

    @Test
    fun `no key label is a filled geometric shape`() {
        val offenders = mutableListOf<String>()
        uiFiles().forEach { file ->
            file.readLines().forEachIndexed { i, line ->
                if (!line.contains("text = \"")) return@forEachIndexed
                val label = line.substringAfter("text = \"").substringBefore('"')
                if (label.isEmpty() || label.contains('$')) return@forEachIndexed
                if (geometricShapes.holdsAny(label)) {
                    offenders += "${file.name}:${i + 1}  \"$label\""
                }
            }
        }
        assertTrue(
            "A key label uses a Geometric Shapes character, which is where the one glyph this app " +
                "was ever reported as missing came from. Use a word:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    private fun uiFiles(): List<File> {
        // Run from the app module, so the sources are a fixed relative walk from here.
        val root = File("src/main/java/com/gios/lightchat/ui")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
