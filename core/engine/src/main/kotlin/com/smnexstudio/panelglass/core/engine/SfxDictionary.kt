package com.smnexstudio.panelglass.core.engine

import android.content.Context
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.TextNorm
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * First lookup tier for sound effects: bundled `assets/sfx_ja.json` / `sfx_ko.json` mapping common
 * onomatopoeia to English comic equivalents. Keys are normalised with [TextNorm.sfxKey], then hiragana is read as
 * katakana (an effect is lettered in either: がらがら is ガラガラ).
 */
@Singleton
class SfxDictionary @Inject constructor(@ApplicationContext private val context: Context) {
    /** Exact keys, and the same keys [loose]: a variant with another ッ or ー still finds its entry. */
    internal class Table(val exact: Map<String, String>, val loose: Map<String, String>)

    private val tables = HashMap<Lang, Table>()

    fun lookup(text: String, src: Lang, tgt: Lang): String? {
        if (tgt != Lang.EN) return null // the bundled tables are English-only
        val table = table(src) ?: return null
        val key = key(text)
        table.exact[key]?.let { return it }
        // Elongation marks and repeats: ドドドド → ドド, ゴゴゴ → ゴゴ.
        val collapsed = collapseRepeats(key)
        table.exact[collapsed]?.let { return it }
        // Another stop or length mark than the entry's: ガツーン, ガツンッ → ガツン.
        return table.loose[loose(collapsed)] ?: table.loose[loose(key)]
    }

    private fun table(src: Lang): Table? {
        val asset = when (src) { Lang.JA -> "sfx_ja.json"; Lang.KO -> "sfx_ko.json"; else -> return null }
        synchronized(tables) {
            tables[src]?.let { return it }
            val loaded = runCatching {
                val text = context.assets.open(asset).bufferedReader().use { it.readText() }
                parse(text)
            }.getOrDefault(Table(emptyMap(), emptyMap()))
            tables[src] = loaded
            return loaded
        }
    }

    private fun collapseRepeats(s: String): String {
        if (s.length < 3) return s
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            var run = 1
            while (i + run < s.length && s[i + run] == c) run++
            sb.append(c); if (run >= 2) sb.append(c)
            i += run
        }
        return sb.toString()
    }

    internal companion object {
        /** A table from its JSON (`{"ドン": "BOOM", …}`); the first entry wins where two keys fold together. */
        fun parse(json: String): Table {
            val exact = LinkedHashMap<String, String>(); val looseMap = LinkedHashMap<String, String>()
            for ((k, v) in Json.parseToJsonElement(json).jsonObject.entries) {
                val key = key(k); val value = v.jsonPrimitive.content
                exact.putIfAbsent(key, value)
                looseMap.putIfAbsent(loose(key), value)
            }
            return Table(exact, looseMap)
        }

        fun key(text: String): String = katakana(TextNorm.sfxKey(text))

        /** Hiragana as katakana (ぁ..ゖ sit 0x60 below ァ..ヶ); everything else as it is. */
        fun katakana(s: String): String = buildString(s.length) {
            for (c in s) append(if (c in 'ぁ'..'ゖ') c + 0x60 else c)
        }

        /** Without small ッ, ー and the long-sound marks, which letterers add or drop freely. */
        fun loose(s: String): String = s.filterNot { it == 'ッ' || it == 'ー' || it == '〜' || it == '～' }
    }
}
