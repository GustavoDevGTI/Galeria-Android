package com.galeria.android

import android.Manifest
import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.hamcrest.Matchers.containsString
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class HiddenAlbumDialogInstrumentedTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO
    )

    @Test
    fun openingDialogDoesNotRevealHiddenAlbumNeverShownBefore() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManager.getInstance(context).cancelAllWork().result.get()
        val dao = GalleryDatabase.get(context).galleryDao()
        val prefs = context.getSharedPreferences("gallery_albums", Context.MODE_PRIVATE)
        val catalogPrefs = context.getSharedPreferences(CATALOG_META_PREFS, Context.MODE_PRIVATE)
        val originalMediaStoreVersion = catalogPrefs.getString(PREF_MEDIA_STORE_VERSION_VISIBLE, null)
        val allFilesAccess = MediaActions.hasAllFilesAccess(context)
        val originalVisible = io { dao.media(VISIBLE_SCOPE) }
        val originalComplete = io { dao.media(COMPLETE_SCOPE) }
        val originalVisibleState = io { dao.state(VISIBLE_SCOPE) }
        val originalCompleteState = io { dao.state(COMPLETE_SCOPE) }
        val originalEverVisible = prefs.getStringSet(PREF_EVER_VISIBLE, null)?.let(::HashSet)
        val originalHidden = prefs.getStringSet(PREF_HIDDEN_KEYS, null)?.let(::HashSet)
        val originalShowHidden = prefs.getBoolean(PREF_SHOW_HIDDEN, false)
        val originalInitialRequest = prefs.getBoolean(PREF_INITIAL_REQUEST, false)
        val originalAllFilesPrompt = prefs.getBoolean(PREF_ALL_FILES_PROMPT, false)

        try {
            io {
                dao.replaceMedia(
                    VISIBLE_SCOPE,
                    listOf(media(VISIBLE_SCOPE, "camera", "Câmera", "DCIM/Camera/", 3)),
                    CatalogStateEntity(VISIBLE_SCOPE, System.currentTimeMillis(), allFilesAccess)
                )
                dao.replaceMedia(
                    COMPLETE_SCOPE,
                    listOf(
                        media(COMPLETE_SCOPE, "camera", "Câmera", "DCIM/Camera/", 3),
                        media(COMPLETE_SCOPE, "hidden-known", "Oculto conhecido", "Pictures/.known/", 2),
                        media(COMPLETE_SCOPE, "hidden-never", "Oculto nunca exibido", "Pictures/.never/", 1)
                    ),
                    CatalogStateEntity(COMPLETE_SCOPE, System.currentTimeMillis(), allFilesAccess)
                )
            }
            catalogPrefs.edit()
                .putString(
                    PREF_MEDIA_STORE_VERSION_VISIBLE,
                    MediaStore.getVersion(context, MediaStore.VOLUME_EXTERNAL)
                )
                .commit()
            prefs.edit()
                .putBoolean(PREF_INITIAL_REQUEST, true)
                .putBoolean(PREF_ALL_FILES_PROMPT, true)
                .putBoolean(PREF_SHOW_HIDDEN, false)
                .putStringSet(PREF_HIDDEN_KEYS, setOf("hidden-known", "hidden-never"))
                .putStringSet(PREF_EVER_VISIBLE, setOf("hidden-known"))
                .commit()

            ActivityScenario.launch(MainActivity::class.java).use {
                waitUntilDisplayedContaining("Câmera")
                onView(withContentDescription("Mais opções")).perform(click())
                waitUntilDisplayed("Exibir/ocultar pastas")
                onView(withText("Exibir/ocultar pastas")).perform(click())

                waitUntilDialogDisplayedContaining("Oculto conhecido")
                onView(withText("Exibir/ocultar pastas")).inRoot(isDialog()).check { view, noViewFoundException ->
                    if (noViewFoundException != null) throw noViewFoundException
                    val root = view.rootView
                    val location = IntArray(2)
                    root.getLocationOnScreen(location)
                    val screenWidth = view.resources.displayMetrics.widthPixels
                    val screenHeight = view.resources.displayMetrics.heightPixels
                    assertTrue("O painel deve nascer afastado da borda esquerda.", location[0] > 0)
                    assertTrue("O painel deve manter formato vertical e compacto.", root.width <= screenWidth * 0.72f)
                    assertTrue("O painel deve começar abaixo da barra superior.", location[1] >= Ui.dp(view.context, 56))
                    assertTrue("O painel deve preservar margem inferior.", root.height < screenHeight * 0.90f)
                    val rightGap = screenWidth - (location[0] + root.width)
                    assertTrue(
                        "O painel deve ficar junto à lateral direita com uma pequena margem.",
                        rightGap in 0..Ui.dp(view.context, 16)
                    )
                }
                onView(withText(containsString("Câmera"))).inRoot(isDialog()).check(matches(isDisplayed()))
                onView(withText(containsString("Oculto nunca exibido"))).inRoot(isDialog()).check(doesNotExist())
                onView(withText("Carregar ocultos")).inRoot(isDialog()).check(matches(isDisplayed()))
            }
        } finally {
            io {
                dao.replaceMedia(
                    VISIBLE_SCOPE,
                    originalVisible,
                    originalVisibleState ?: CatalogStateEntity(VISIBLE_SCOPE, System.currentTimeMillis(), false)
                )
                dao.replaceMedia(
                    COMPLETE_SCOPE,
                    originalComplete,
                    originalCompleteState ?: CatalogStateEntity(COMPLETE_SCOPE, System.currentTimeMillis(), true)
                )
            }
            val editor = prefs.edit()
                .putBoolean(PREF_SHOW_HIDDEN, originalShowHidden)
                .putBoolean(PREF_INITIAL_REQUEST, originalInitialRequest)
                .putBoolean(PREF_ALL_FILES_PROMPT, originalAllFilesPrompt)
            if (originalEverVisible == null) editor.remove(PREF_EVER_VISIBLE) else editor.putStringSet(PREF_EVER_VISIBLE, originalEverVisible)
            if (originalHidden == null) editor.remove(PREF_HIDDEN_KEYS) else editor.putStringSet(PREF_HIDDEN_KEYS, originalHidden)
            editor.commit()
            if (originalMediaStoreVersion == null) {
                catalogPrefs.edit().remove(PREF_MEDIA_STORE_VERSION_VISIBLE).commit()
            } else {
                catalogPrefs.edit().putString(PREF_MEDIA_STORE_VERSION_VISIBLE, originalMediaStoreVersion).commit()
            }
        }
    }

    private fun media(scope: String, key: String, name: String, path: String, date: Long) = CachedMediaEntity(
        scope = scope,
        uri = "content://hidden-dialog/$scope/$key",
        mediaId = date,
        name = "$key.jpg",
        mimeType = "image/jpeg",
        dateAdded = date,
        size = 100,
        relativePath = path,
        albumKey = key,
        albumName = name
    )

    private fun <T> io(block: () -> T): T = runBlocking { withContext(Dispatchers.IO) { block() } }

    private fun waitUntilDisplayed(text: String) = waitForView {
        onView(withText(text)).check(matches(isDisplayed()))
    }

    private fun waitUntilDisplayedContaining(text: String) = waitForView {
        onView(withText(containsString(text))).check(matches(isDisplayed()))
    }

    private fun waitUntilDialogDisplayedContaining(text: String) = waitForView {
        onView(withText(containsString(text))).inRoot(isDialog()).check(matches(isDisplayed()))
    }

    private fun waitForView(assertion: () -> Unit) {
        val deadline = System.currentTimeMillis() + 10_000L
        var lastFailure: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                assertion()
                return
            } catch (failure: Throwable) {
                lastFailure = failure
                Thread.sleep(100L)
            }
        }
        throw AssertionError("Conteúdo esperado não exibido.", lastFailure)
    }

    private companion object {
        const val VISIBLE_SCOPE = "visible"
        const val COMPLETE_SCOPE = "complete"
        const val PREF_EVER_VISIBLE = "ever_visible_folder_keys"
        const val PREF_HIDDEN_KEYS = "hidden_folder_keys"
        const val PREF_SHOW_HIDDEN = "show_hidden_folders"
        const val PREF_INITIAL_REQUEST = "initial_all_files_requested"
        const val PREF_ALL_FILES_PROMPT = "all_files_prompted"
        const val CATALOG_META_PREFS = "gallery_catalog_meta"
        const val PREF_MEDIA_STORE_VERSION_VISIBLE = "media_store_version_visible"
    }
}
