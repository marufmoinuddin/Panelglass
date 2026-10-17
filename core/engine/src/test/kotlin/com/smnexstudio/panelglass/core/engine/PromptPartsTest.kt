package com.smnexstudio.panelglass.core.engine

import com.smnexstudio.panelglass.core.engine.llm.LlmContract
import com.smnexstudio.panelglass.core.engine.local.LocalPrompt
import com.smnexstudio.panelglass.core.model.ContextPair
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.RegionItem
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.TranslateRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptPartsTest {
    private val glossary = linkedMapOf("ミナ" to "Mina", "先輩" to "senpai")
    private val prior = listOf(ContextPair("一", "One."), ContextPair("二", "Two."), ContextPair("三", "Three."))

    @Test
    fun rendersTermsAndTheLastTwoLines() {
        assertEquals("- ミナ => Mina\n- 先輩 => senpai\n", PromptParts.glossaryList(glossary))
        assertEquals("ミナ => Mina; 先輩 => senpai", PromptParts.glossaryInline(glossary))
        assertEquals("- 二 => Two.\n- 三 => Three.\n", PromptParts.context(prior))
        assertEquals("", PromptParts.glossaryList(emptyMap()))
    }

    @Test
    fun cloudAndOnDevicePromptsShowTheSameRenderings() {
        val req = TranslateRequest(listOf(RegionItem(0, "p", RegionKind.ENCLOSED, "はい")), Lang.JA, Lang.EN, glossary, prior)
        val cloud = LlmContract.systemPrompt(req)
        val local = LocalPrompt.system(req)
        assertTrue(cloud.contains("Glossary (always use these renderings):\n- ミナ => Mina\n- 先輩 => senpai\n"))
        assertTrue(local.contains("Glossary: ミナ => Mina; 先輩 => senpai\n"))
        for (p in listOf(cloud, local)) {
            assertTrue(p.contains("- 二 => Two.\n- 三 => Three.\n"))
            assertTrue(!p.contains("One."))
        }
    }
}
