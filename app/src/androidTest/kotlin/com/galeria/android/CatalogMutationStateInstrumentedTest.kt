package com.galeria.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogMutationStateInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = context.getSharedPreferences("gallery_catalog_meta", Context.MODE_PRIVATE)
    private val prefix = "catalog_dirty_after_media_action"
    private val keys = listOf(prefix, "${prefix}_visible", "${prefix}_complete")
    private lateinit var original: Map<String, *>

    @Before fun saveState() {
        original = preferences.all
        GalleryCatalogStore.clearCatalogDirty(context)
    }

    @After fun restoreState() {
        preferences.edit().apply {
            keys.forEach { key ->
                if (original.containsKey(key)) putBoolean(key, original[key] as Boolean) else remove(key)
            }
        }.commit()
    }

    @Test fun refreshingVisibleDoesNotMarkHiddenCatalogAsFresh() {
        GalleryCatalogStore.markCatalogDirty(context)
        GalleryCatalogStore.clearCatalogDirty(context, false, GalleryCatalogStore.currentMutationRevision())
        assertFalse(GalleryCatalogStore.isCatalogDirty(context, false))
        assertTrue(GalleryCatalogStore.isCatalogDirty(context, true))
    }

    @Test fun olderScanCannotClearANewerMutation() {
        GalleryCatalogStore.markCatalogDirty(context)
        val revision = GalleryCatalogStore.currentMutationRevision()
        GalleryCatalogStore.markCatalogDirty(context)
        GalleryCatalogStore.clearCatalogDirty(context, false, revision)
        assertTrue(GalleryCatalogStore.isCatalogDirty(context, false))
    }

    @Test fun legacyDirtyFlagKeepsOtherScopePendingAfterFirstRefresh() {
        preferences.edit().putBoolean(prefix, true).commit()
        GalleryCatalogStore.clearCatalogDirty(context, false, GalleryCatalogStore.currentMutationRevision())
        assertFalse(GalleryCatalogStore.isCatalogDirty(context, false))
        assertTrue(GalleryCatalogStore.isCatalogDirty(context, true))
    }
}
