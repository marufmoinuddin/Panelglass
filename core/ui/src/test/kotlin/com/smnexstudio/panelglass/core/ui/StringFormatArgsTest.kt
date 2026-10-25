package com.smnexstudio.panelglass.core.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The string resources: a string that is formatted with fewer arguments than it needs crashes the screen that shows
 * it, and a translation that does not use the same arguments as the default crashes it too.
 *
 * The files are read straight from `src/main/res`; no Android runtime is needed.
 */
class StringFormatArgsTest {
    private val res: File = File("src/main/res")

    /** The strings of one file: name to the body. */
    private fun strings(dir: File): Map<String, String> {
        val file = File(dir, "strings.xml")
        if (!file.exists()) return emptyMap()
        val out = mutableMapOf<String, String>()
        for (m in STRING.findAll(file.readText())) {
            out[m.groupValues[1]] = m.groupValues[2]
        }
        return out
    }

    /** The positional indexes (%1$s, %2$d, ...) a body uses. */
    private fun indexes(body: String): Set<Int> =
        POSITIONAL.findAll(body).map { it.groupValues[1].toInt() }.toSet()

    /** Whether the body has a specifier without an explicit index (%s, %d). */
    private fun hasUnindexed(body: String): Boolean =
        UNINDEXED.containsMatchIn(POSITIONAL.replace(body, ""))

    @Test
    fun everyTranslationUsesTheSameArgumentsAsTheDefault() {
        val default = strings(File(res, "values"))
        assertTrue(default.isNotEmpty(), "the default strings.xml could not be read")

        val mismatches = mutableListOf<String>()
        for (dir in res.listFiles()?.filter { it.isDirectory && it.name.startsWith("values-") } ?: emptyList()) {
            val locale = strings(dir)
            for ((name, body) in locale) {
                val d = default[name] ?: continue
                if (indexes(body) != indexes(d)) {
                    mismatches += "${dir.name}/$name: uses ${indexes(body).sorted()}, the default uses ${indexes(d).sorted()}"
                }
                if (hasUnindexed(body) != hasUnindexed(d)) {
                    mismatches += "${dir.name}/$name: mixes indexed and unindexed arguments differently from the default"
                }
            }
        }
        assertEquals(emptyList<String>(), mismatches, "a translation formats differently from the default")
    }

    @Test
    fun noStringMixesIndexedAndUnindexedArguments() {
        // Android reads %1$s and %s differently: a string with both is formatted wrongly or throws.
        val mixed = mutableListOf<String>()
        for (dir in res.listFiles()?.filter { it.isDirectory && it.name.startsWith("values") } ?: emptyList()) {
            for ((name, body) in strings(dir)) {
                if (hasUnindexed(body) && indexes(body).isNotEmpty()) {
                    mixed += "${dir.name}/$name"
                }
            }
        }
        assertEquals(emptyList<String>(), mixed, "a string mixes positional and non-positional arguments")
    }

    @Test
    fun everyIndexedStringUsesEveryIndexItDeclares() {
        // %2$s without %1$s leaves a hole: Android throws when the first argument is missing.
        val holes = mutableListOf<String>()
        for (dir in res.listFiles()?.filter { it.isDirectory && it.name.startsWith("values") } ?: emptyList()) {
            for ((name, body) in strings(dir)) {
                val idx = indexes(body)
                if (idx.isEmpty()) continue
                val missing = (1..idx.max()).filter { it !in idx }
                if (missing.isNotEmpty()) holes += "${dir.name}/$name: misses ${missing.sorted()}"
            }
        }
        assertEquals(emptyList<String>(), holes, "a string leaves an argument index unused")
    }

    @Test
    fun everyCallSitePassesTheArgumentsItsStringNeeds() {
        // A call with fewer arguments than the string declares throws MissingFormatArgumentException and takes the
        // screen down. This is the crash that came from the import progress row.
        val root = repoRoot()
        assertTrue(root != null, "the repository root could not be found")
        val repo = root!!
        val resources = strings(File(res, "values"))
        val bad = mutableListOf<String>()

        for (dir in listOf("app", "core", "feature")) {
            val base = File(repo, dir)
            if (!base.isDirectory) continue
            base.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { file ->
                val src = file.readText()
                for (m in CALL.findAll(src)) {
                    val args = splitArgs(src, m.range.last)
                    if (args.isEmpty()) continue
                    val key = KEY.matchEntire(args[0]) ?: continue
                    val name = key.groupValues[1]
                    val need = indexes(resources[name] ?: continue)
                    if (args.size - 1 < (need.maxOrNull() ?: 0)) {
                        val line = src.take(m.range.first).count { it == '\n' } + 1
                        bad += "${file.relativeTo(repo)}:$line $name gets ${args.size - 1} arguments, needs ${need.max()}"
                    }
                }
            }
        }
        assertEquals(emptyList<String>(), bad, "a call site formats a string with too few arguments")
    }

    /** The repository root: two levels up from the module directory. */
    private fun repoRoot(): File? {
        var dir: File? = File("").absoluteFile
        repeat(4) {
            if (dir != null && File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir?.parentFile
        }
        return null
    }

    /**
     * The arguments of a call whose '(' is at [open]: the text of each argument at depth one.
     */
    private fun splitArgs(src: String, open: Int): List<String> {
        val args = mutableListOf<String>()
        val cur = StringBuilder()
        var depth = 0
        var i = open
        var inString: Char? = null
        while (i < src.length) {
            val c = src[i]
            if (inString != null) {
                cur.append(c)
                if (c == '\\' && i + 1 < src.length) { cur.append(src[i + 1]); i += 2; continue }
                if (c == inString) inString = null
                i++
                continue
            }
            when (c) {
                '"', '\'' -> { inString = c; cur.append(c) }
                '(' -> { depth++; if (depth > 1) cur.append(c) }
                ')' -> {
                    depth--
                    if (depth == 0) { args += cur.toString().trim(); return args }
                    cur.append(c)
                }
                ',' -> if (depth == 1) { args += cur.toString().trim(); cur.setLength(0) } else cur.append(c)
                else -> cur.append(c)
            }
            i++
        }
        return args
    }

    private companion object {
        val STRING = Regex("<string\\s+name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
        val POSITIONAL = Regex("%(\\d+)\\$")
        val UNINDEXED = Regex("%(?:-|\\+|#| |0)?\\d*(?:\\.\\d+)?[sdfx]")
        val CALL = Regex("\\b(?:stringResource|getString|pluralStringResource)\\s*\\(")
        val KEY = Regex("(?:[A-Za-z_][A-Za-z0-9_]*\\.)*string\\.([A-Za-z0-9_]+)")
    }
}
