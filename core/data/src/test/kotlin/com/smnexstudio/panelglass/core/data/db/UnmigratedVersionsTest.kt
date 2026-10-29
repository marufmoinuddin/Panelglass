package com.smnexstudio.panelglass.core.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.smnexstudio.panelglass.core.data.di.DataModule
import com.smnexstudio.panelglass.core.model.GlossaryEntry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The database exactly as the app opens it ([DataModule.db]) when the file on the phone has no migration path: a
 * version from before 3 (no Migration was written for it) or a newer one (an older APK installed over a newer build).
 * Either is rebuilt empty at version [CURRENT] rather than crashing the launch. Versions 3 and 4 migrate
 * ([Migration3To4Test], [Migration4To5Test]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnmigratedVersionsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "panelglass.db"

    @Before @After fun clean() { context.deleteDatabase(name) }

    private fun writeOldFile(version: Int) {
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null)
        db.execSQL("CREATE TABLE legacy_thing (id INTEGER PRIMARY KEY, value TEXT)")
        db.execSQL("INSERT INTO legacy_thing (value) VALUES ('invented')")
        db.version = version
        db.close()
    }

    private fun tables(db: PanelglassDb): Set<String> =
        db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }

    private fun check(version: Int) = runBlocking {
        writeOldFile(version)
        val db = DataModule.db(context)
        try {
            val t = tables(db)
            assertFalse("the old file was dropped", "legacy_thing" in t)
            assertTrue(t.containsAll(listOf("sites", "glossary", "series_notes", "history")))
            assertEquals(CURRENT, db.openHelper.readableDatabase.version)
            // Usable straight away.
            db.glossary().upsert(GlossaryEntry(seriesKey = "s", source = "a", target = "b"))
            assertEquals(1, db.glossary().forSeries("s").size)
        } finally {
            db.close()
        }
    }

    @Test fun aVersionWithNoMigrationIsRebuiltEmptyNotACrash() = check(version = 2)

    @Test fun aNewerDatabaseFromALaterBuildIsRebuiltOnDowngrade() = check(version = CURRENT + 1)

    private companion object { const val CURRENT = 5 }
}
