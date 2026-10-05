package com.galeria.android

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.view.Gravity
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withParent
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import androidx.work.WorkManager
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.allOf
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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
        // Dedicated test installation, as in AlbumMutationInstrumentedTest.
        // Revocation during instrumentation would kill its process.
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow"
        ).use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        WorkManager.getInstance(context).cancelAllWork().result.get()
        val prefs = context.getSharedPreferences("gallery_albums", Context.MODE_PRIVATE)
        val allFilesAccess = MediaActions.hasAllFilesAccess(context)
        val suffix = System.nanoTime()
        val cameraName = "GaleriaDialogCamera-$suffix"
        val knownName = "GaleriaDialogKnown-$suffix"
        val neverName = "GaleriaDialogNever-$suffix"
        val cameraKey = "Pictures/$cameraName/"
        val knownKey = "Pictures/.$knownName/"
        val neverKey = "Pictures/.$neverName/"
        val fixtureUris = mutableListOf<android.net.Uri>()
        val hiddenFiles = mutableListOf<java.io.File>()
        val originalEverVisible = prefs.getStringSet(PREF_EVER_VISIBLE, null)?.let(::HashSet)
        val originalHidden = prefs.getStringSet(PREF_HIDDEN_KEYS, null)?.let(::HashSet)
        val originalShowHidden = prefs.getBoolean(PREF_SHOW_HIDDEN, false)
        val originalInitialRequest = prefs.getBoolean(PREF_INITIAL_REQUEST, false)
        val originalAllFilesPrompt = prefs.getBoolean(PREF_ALL_FILES_PROMPT, false)
        val originalSort = prefs.getString("sort_mode", null)
        val originalDescending = prefs.getBoolean("sort_desc", true)
        val hadDescending = prefs.contains("sort_desc")

        try {
            // Use real indexed media: a valid cached catalog can legitimately
            // become stale while the preceding test's MediaStore work finishes.
            // The privacy contract must survive that reconciliation, not depend
            // on synthetic rows which do not exist on the device.
            for (key in listOf(cameraKey, knownKey, neverKey)) {
                if (key != cameraKey) {
                    // MediaStore sanitizes dot-directory names on insertion.
                    // Create genuinely hidden filesystem fixtures instead.
                    val directory = java.io.File(android.os.Environment.getExternalStorageDirectory(), key)
                    assertTrue(directory.mkdirs())
                    hiddenFiles.add(directory)
                    val marker = java.io.File(directory, ".nomedia")
                    assertTrue(marker.createNewFile())
                    hiddenFiles.add(marker)
                    val file = java.io.File(directory, "dialog-$suffix.png")
                    hiddenFiles.add(file)
                    file.outputStream().use(::writeFixtureImage)
                    assertTrue(file.setLastModified(System.currentTimeMillis() + 3_600_000L))
                    continue
                }
                val uri = requireNotNull(context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "dialog-$suffix.png")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, key)
                        put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000 + 3600)
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                ))
                fixtureUris.add(uri)
                requireNotNull(context.contentResolver.openOutputStream(uri)).use(::writeFixtureImage)
                context.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null)
            }
            val actualMedia = MediaStoreRepository.refreshMedia(context, force = true)
            val hiddenMedia = listOf(knownKey, neverKey).flatMap { MediaStoreRepository.queryAlbumMedia(context, it, true) }
            GalleryCatalogStore.writeMedia(context, actualMedia.filterNot { it.albumKey in setOf(knownKey, neverKey) } + hiddenMedia,
                true, allFilesAccess)
            prefs.edit()
                .putBoolean(PREF_INITIAL_REQUEST, true)
                .putBoolean(PREF_ALL_FILES_PROMPT, true)
                .putBoolean(PREF_SHOW_HIDDEN, false)
                .putString("sort_mode", "modified")
                .putBoolean("sort_desc", true)
                .putStringSet(PREF_HIDDEN_KEYS, setOf(knownKey, neverKey))
                .putStringSet(PREF_EVER_VISIBLE, setOf(knownKey))
                .commit()
            GalleryCatalogStore.clearCatalogDirty(context)

            ActivityScenario.launch(MainActivity::class.java).use {
                waitUntilDisplayedContaining(cameraName)
                onView(withContentDescription("Mais opções")).perform(click())
                waitUntilDisplayed("Exibir/ocultar pastas")
                onView(withText("Exibir ocultos")).check(doesNotExist())
                onView(withText("Exibir/ocultar pastas")).perform(clickClickableAncestor())

                waitUntilDialogDisplayedContaining(knownName)
                onView(withText("Exibir/ocultar pastas")).inRoot(isDialog()).check { view, noViewFoundException ->
                    if (noViewFoundException != null) throw noViewFoundException
                    val root = view.rootView
                    val location = IntArray(2)
                    root.getLocationOnScreen(location)
                    val screenWidth = view.resources.displayMetrics.widthPixels
                    val screenHeight = view.resources.displayMetrics.heightPixels
                    assertTrue("O painel deve nascer afastado da borda esquerda.", location[0] > 0)
                    assertTrue("O painel central deve preservar margens laterais.", root.width <= screenWidth * 0.94f)
                    assertTrue("O painel deve começar abaixo da barra superior.", location[1] >= Ui.dp(view.context, 56))
                    assertTrue("O painel deve preservar margem inferior.", root.height < screenHeight * 0.90f)
                    val rightGap = screenWidth - (location[0] + root.width)
                    assertTrue(
                        "O gerenciador de ocultos deve ficar centralizado, com margens equivalentes.",
                        kotlin.math.abs(location[0] - rightGap) <= Ui.dp(view.context, 8)
                    )
                }
                onView(withText(containsString(cameraName))).inRoot(isDialog()).check(matches(isDisplayed()))
                onView(withText(containsString(neverName))).inRoot(isDialog()).check(doesNotExist())
                onView(withText("Carregar ocultos")).inRoot(isDialog()).check(matches(isDisplayed()))
                onView(withText("OK")).inRoot(isDialog()).check { view, exception ->
                    if (exception != null) throw exception
                    val gravity = Gravity.getAbsoluteGravity(
                        (view as TextView).gravity,
                        view.layoutDirection
                    ) and Gravity.HORIZONTAL_GRAVITY_MASK
                    assertTrue("O OK do painel central deve ficar à direita.", gravity == Gravity.RIGHT)
                }
                val hiddenControlLocation = IntArray(2)
                val loadControlLocation = IntArray(2)
                onView(withText("Exibir ocultos")).inRoot(isDialog()).check { view, exception ->
                    if (exception != null) throw exception
                    view.getLocationOnScreen(hiddenControlLocation)
                    hiddenControlLocation[1] += view.height / 2
                }
                onView(withText("Carregar ocultos")).inRoot(isDialog()).check { view, exception ->
                    if (exception != null) throw exception
                    view.getLocationOnScreen(loadControlLocation)
                    loadControlLocation[1] += view.height / 2
                }
                assertTrue(
                    "Os centros de Exibir ocultos e Carregar ocultos devem ficar na mesma linha.",
                    kotlin.math.abs(hiddenControlLocation[1] - loadControlLocation[1]) <= Ui.dp(context, 8)
                )
            }

            val revealName = "GaleriaRevealTest${System.nanoTime()}"
            val revealUri = requireNotNull(context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "reveal.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$revealName/")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            ))
            try {
                context.contentResolver.openOutputStream(revealUri)?.use { it.write(byteArrayOf(1, 2, 3, 4)) }
                context.contentResolver.update(revealUri, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null)
                MediaStoreRepository.invalidateCache()
                val revealKey = MediaStoreRepository.refreshMedia(context, force = true)
                    .first { MediaIdentityRules.sameUri(it.uri.toString(), revealUri.toString()) }.albumKey
                prefs.edit()
                    .putStringSet(PREF_HIDDEN_KEYS, setOf(revealKey, knownKey, neverKey))
                    .putStringSet(PREF_EVER_VISIBLE,
                        prefs.getStringSet(PREF_EVER_VISIBLE, emptySet()).orEmpty() + revealKey)
                    .commit()
                ActivityScenario.launch(MainActivity::class.java).use {
                    onView(withContentDescription("Mais opções")).perform(click())
                    onView(withText("Exibir/ocultar pastas")).perform(clickClickableAncestor())
                    waitUntilDialogDisplayedContaining(revealName)
                    onView(allOf(withContentDescription("Exibir por 30 minutos"),
                        withParent(hasDescendant(withText(containsString(revealName))))))
                        .inRoot(isDialog()).perform(clickClickableAncestor())
                    assertTrue("O toque no olho deve ativar a visualização temporária.",
                        TemporaryAlbumVisibility.activeKeys().contains(revealKey))
                    waitForView {
                        onView(withContentDescription("Mais opções")).check(matches(isDisplayed()))
                    }
                    waitUntilDisplayedContaining(revealName)
                    assertTrue(prefs.getStringSet(PREF_HIDDEN_KEYS, emptySet()).orEmpty().contains(revealKey))

                    onView(withContentDescription("Mais opções")).perform(click())
                    onView(withText("Exibir/ocultar pastas")).perform(clickClickableAncestor())
                    waitUntilDialogDisplayedContaining(revealName)
                    onView(allOf(withContentDescription("Ocultar novamente"),
                        withParent(hasDescendant(withText(containsString(revealName))))))
                        .inRoot(isDialog()).perform(clickClickableAncestor())
                    assertFalse("O segundo toque deve ocultar o álbum novamente.",
                        TemporaryAlbumVisibility.activeKeys().contains(revealKey))
                    waitForView {
                        onView(withContentDescription("Mais opções")).check(matches(isDisplayed()))
                    }
                    waitForView {
                        onView(withText(containsString(revealName))).check(doesNotExist())
                    }
                    onView(withContentDescription("Mais opções")).perform(click())
                    onView(withText("Exibir/ocultar pastas")).perform(clickClickableAncestor())
                    waitUntilDialogDisplayedContaining(revealName)
                    onView(allOf(withContentDescription("Exibir por 30 minutos"),
                        withParent(hasDescendant(withText(containsString(revealName))))))
                        .inRoot(isDialog()).perform(clickClickableAncestor())
                    waitForView {
                        onView(withContentDescription("Mais opções")).check(matches(isDisplayed()))
                    }
                    waitUntilDisplayedContaining(revealName)
                }
                assertFalse(TemporaryAlbumVisibility.activeKeys().contains(revealKey))
            } finally {
                context.contentResolver.delete(revealUri, null, null)
                MediaStoreRepository.invalidateCache()
                GalleryCatalogStore.markCatalogDirty(context)
            }
        } finally {
            fixtureUris.forEach { context.contentResolver.delete(it, null, null) }
            hiddenFiles.asReversed().forEach { assertTrue("Remover apenas a fixture própria: $it", it.delete()) }
            val editor = prefs.edit()
                .putBoolean(PREF_SHOW_HIDDEN, originalShowHidden)
                .putBoolean(PREF_INITIAL_REQUEST, originalInitialRequest)
                .putBoolean(PREF_ALL_FILES_PROMPT, originalAllFilesPrompt)
            if (originalSort == null) editor.remove("sort_mode") else editor.putString("sort_mode", originalSort)
            if (hadDescending) editor.putBoolean("sort_desc", originalDescending) else editor.remove("sort_desc")
            if (originalEverVisible == null) editor.remove(PREF_EVER_VISIBLE) else editor.putStringSet(PREF_EVER_VISIBLE, originalEverVisible)
            if (originalHidden == null) editor.remove(PREF_HIDDEN_KEYS) else editor.putStringSet(PREF_HIDDEN_KEYS, originalHidden)
            editor.commit()
            MediaStoreRepository.invalidateCache()
            GalleryCatalogStore.markCatalogDirty(context)
        }
    }

    private fun writeFixtureImage(stream: java.io.OutputStream) {
        val bitmap = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.BLUE)
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream))
        } finally { bitmap.recycle() }
    }

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
        const val PREF_EVER_VISIBLE = "ever_visible_folder_keys"
        const val PREF_HIDDEN_KEYS = "hidden_folder_keys"
        const val PREF_SHOW_HIDDEN = "show_hidden_folders"
        const val PREF_INITIAL_REQUEST = "initial_all_files_requested"
        const val PREF_ALL_FILES_PROMPT = "all_files_prompted"
    }
}
