package com.smnexstudio.panelglass.core.ui

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The app's UI language (CLAUDE.md › App language): English until the user picks another, never the phone's language;
 * the choice survives a restart, on Android 12 through the app's own preferences and on 13+ through the system's
 * per-app language. Robolectric on API 31 and 34 (JUnit 4 via vintage).
 */
@RunWith(RobolectricTestRunner::class)
class AppLocaleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val phone = Locale.getDefault()

    @Before fun clear() {
        context.getSharedPreferences("app_locale", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun restore() = Locale.setDefault(phone)

    @Test fun localesAreNormalisedToTheUiLanguages() {
        assertEquals("ja", AppLocale.normalize(Locale.forLanguageTag("ja-JP")))
        assertEquals("zh-Hant", AppLocale.normalize(Locale.forLanguageTag("zh-TW")))
        assertEquals("zh-Hant", AppLocale.normalize(Locale.forLanguageTag("zh-Hant-CN")))
        assertEquals("zh-Hans", AppLocale.normalize(Locale.forLanguageTag("zh-CN")))
        assertEquals("zh-Hans", AppLocale.normalize(Locale.forLanguageTag("zh")))
        assertEquals("id", AppLocale.normalize(Locale("in", "ID")))
        assertEquals("a language the app does not have is English", "en", AppLocale.normalize(Locale.forLanguageTag("sw-KE")))
    }

    /** A French phone still opens in English on Android 12. */
    @Config(sdk = [31], qualifiers = "fr-rFR")
    @Test fun android12StartsInEnglishWhateverThePhoneLanguage() {
        Locale.setDefault(Locale.FRANCE)
        assertEquals("en", AppLocale.current(context))
        assertEquals(Locale.ENGLISH.language, AppLocale.wrap(context).resources.configuration.locales[0].language)
    }

    /** Android 12: the choice is applied by the app itself and read back after a restart. */
    @Config(sdk = [31])
    @Test fun android12KeepsTheChoiceAcrossARestart() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        AppLocale.set(activity, "ja")
        assertEquals("ja", AppLocale.current(context))
        assertEquals("ja", AppLocale.wrap(context).resources.configuration.locales[0].language)
        assertEquals("the default locale follows (view models format with it)", "ja", Locale.getDefault().language)
    }

    /** A stored tag that is not a UI language (an older build's, or a damaged file) reads as English. */
    @Config(sdk = [31])
    @Test fun anUnknownStoredLanguageReadsAsEnglish() {
        context.getSharedPreferences("app_locale", Context.MODE_PRIVATE).edit().putString("tag", "xx").commit()
        assertEquals("en", AppLocale.current(context))
    }

    /** Android 13+: a first start puts the app on English, not the phone's language. */
    @Config(sdk = [34], qualifiers = "de-rDE")
    @Test fun android13FirstStartSetsEnglishAsTheAppLanguage() {
        val lm = context.getSystemService(LocaleManager::class.java)
        lm.applicationLocales = LocaleList.getEmptyLocaleList()
        AppLocale.init(context)
        assertEquals("en", lm.applicationLocales.toLanguageTags())
        assertEquals("en", AppLocale.current(context))
    }

    /** Android 13+: a language set in the system's per-app settings is the app's, and is saved for later. */
    @Config(sdk = [34])
    @Test fun android13FollowsThePerAppLanguageSetInTheSystem() {
        val lm = context.getSystemService(LocaleManager::class.java)
        lm.applicationLocales = LocaleList.forLanguageTags("ko-KR")
        AppLocale.init(context)
        assertEquals("ko", AppLocale.current(context))
        lm.applicationLocales = LocaleList.getEmptyLocaleList()
        AppLocale.init(context)
        assertEquals("the saved choice is restored when the system forgets it", "ko", lm.applicationLocales.toLanguageTags())
    }

    @Config(sdk = [34])
    @Test fun android13SetGoesThroughTheSystem() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        AppLocale.set(activity, "ar")
        assertEquals("ar", context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags())
        assertEquals("ar", AppLocale.current(context))
    }
}
