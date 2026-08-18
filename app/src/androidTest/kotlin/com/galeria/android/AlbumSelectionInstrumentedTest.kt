package com.galeria.android

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class AlbumSelectionInstrumentedTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO
    )

    @Test
    fun tappingMediaWhileSelectingOnlyChangesSelection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = System.currentTimeMillis()
        val albumPath = "Pictures/GaleriaSelectionTest-$suffix/"
        val firstName = "selecao-primeira-$suffix.png"
        val secondName = "selecao-segunda-$suffix.png"
        val targetPath = "Pictures/GaleriaSelectionTarget-$suffix/"
        val targetName = "destino-$suffix.png"
        val hiddenPath = "Pictures/GaleriaSelectionHidden-$suffix/"
        val hiddenName = "oculto-$suffix.png"
        val firstUri = insertImage(context, albumPath, firstName)
        val secondUri = insertImage(context, albumPath, secondName)
        val targetUri = insertImage(context, targetPath, targetName)
        val hiddenUri = insertImage(context, hiddenPath, hiddenName)
        val prefs = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
        val originalHiddenKeys = HashSet(prefs.getStringSet("hidden_folder_keys", emptySet()).orEmpty())
        prefs.edit().putStringSet(
            "hidden_folder_keys",
            HashSet(originalHiddenKeys).apply { add(hiddenPath) }
        ).commit()
        val dao = GalleryDatabase.get(context).galleryDao()
        val original = runBlocking { withContext(Dispatchers.IO) { dao.media(VISIBLE_SCOPE) } }
        val originalState = runBlocking { withContext(Dispatchers.IO) { dao.state(VISIBLE_SCOPE) } }

        try {
            runBlocking {
                withContext(Dispatchers.IO) {
                    dao.replaceMedia(
                        VISIBLE_SCOPE,
                        listOf(
                            cached(firstUri, firstName, albumPath, suffix + 1),
                            cached(secondUri, secondName, albumPath, suffix),
                            cached(targetUri, targetName, targetPath, suffix - 1, "Destino"),
                            cached(hiddenUri, hiddenName, hiddenPath, suffix - 2, "Oculto")
                        ),
                        CatalogStateEntity(VISIBLE_SCOPE, System.currentTimeMillis(), false)
                    )
                }
            }
            MediaStoreRepository.invalidateCache()
            val intent = Intent(context, AlbumMediaActivity::class.java).apply {
                putExtra("album_key", albumPath)
                putExtra("album_name", "Seleção")
            }

            ActivityScenario.launch<AlbumMediaActivity>(intent).use {
                waitUntilDisplayed(firstName)
                onView(withContentDescription(firstName)).perform(longClick())
                waitUntilHint("1 selecionados")
                onView(withContentDescription("Selecionar todos")).check(matches(isDisplayed()))
                listOf("Compartilhar", "Favoritar", "Excluir", "Mover").forEach { action ->
                    onView(withContentDescription(action)).check { view, noViewFoundException ->
                        if (noViewFoundException != null) throw noViewFoundException
                        val icon = view.findViewWithTag<ImageView>("selection_action_icon")
                        assertEquals(
                            Ui.selectionActionIcon(context),
                            icon.imageTintList?.defaultColor
                        )
                        assertEquals(action, view.findViewWithTag<TextView>("selection_action_label").text.toString())
                        assertEquals(Color.TRANSPARENT, (view.background as ColorDrawable).color)
                        if (action == "Compartilhar") {
                            val dock = view.parent as LinearLayout
                            assertEquals("A barra deve ter quatro ações e três divisórias.", 7, dock.childCount)
                            val fullBar = dock.parent as View
                            assertEquals("O conjunto de ações deve preencher toda a barra inferior.", fullBar.width, dock.width)
                            assertEquals("A barra não deve deixar recorte na lateral esquerda.", 0, fullBar.paddingLeft)
                            assertEquals("A barra não deve deixar recorte na lateral direita.", 0, fullBar.paddingRight)
                            assertEquals(Ui.surface(context), (fullBar.background as ColorDrawable).color)
                            assertEquals(Ui.surface(context), (dock.background as ColorDrawable).color)
                        }
                    }
                }
                onView(withContentDescription(secondName)).perform(click())

                waitUntilHint("2 selecionados")
                onView(withContentDescription("Mover")).check(matches(isDisplayed())).perform(click())
                waitUntilText("Mover para")
                onView(withContentDescription("Álbum Oculto")).check(doesNotExist())
                onView(withContentDescription("Álbum GaleriaSelectionHidden-$suffix")).check(doesNotExist())
            }
        } finally {
            runBlocking {
                withContext(Dispatchers.IO) {
                    dao.replaceMedia(
                        VISIBLE_SCOPE,
                        original,
                        originalState ?: CatalogStateEntity(VISIBLE_SCOPE, System.currentTimeMillis(), false)
                    )
                }
            }
            context.contentResolver.delete(firstUri, null, null)
            context.contentResolver.delete(secondUri, null, null)
            context.contentResolver.delete(targetUri, null, null)
            context.contentResolver.delete(hiddenUri, null, null)
            prefs.edit().putStringSet("hidden_folder_keys", originalHiddenKeys).commit()
            MediaStoreRepository.invalidateCache()
        }
    }

    private fun cached(uri: Uri, name: String, albumPath: String, date: Long, albumName: String = "Seleção") = CachedMediaEntity(
        scope = VISIBLE_SCOPE,
        uri = uri.toString(),
        mediaId = date,
        name = name,
        mimeType = "image/png",
        dateAdded = date,
        size = ONE_PIXEL_PNG.size.toLong(),
        relativePath = albumPath,
        albumKey = albumPath,
        albumName = albumName
    )

    private fun insertImage(context: Context, albumPath: String, name: String): Uri {
        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, albumPath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        )
        assertNotNull(uri)
        val mediaUri = requireNotNull(uri)
        context.contentResolver.openOutputStream(mediaUri)?.use { it.write(ONE_PIXEL_PNG) }
        context.contentResolver.update(
            mediaUri,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null
        )
        return mediaUri
    }

    private fun waitUntilDisplayed(text: String) = waitForView {
        onView(withContentDescription(text)).check(matches(isDisplayed()))
    }

    private fun waitUntilHint(hint: String) = waitForView {
        onView(withContentDescription("Pesquisar nesta pasta")).check(matches(withHint(hint)))
    }

    private fun waitUntilText(text: String) = waitForView {
        onView(withText(text)).check(matches(isDisplayed()))
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
        throw AssertionError("Estado da seleção não exibido.", lastFailure)
    }

    private companion object {
        const val VISIBLE_SCOPE = "visible"
        val ONE_PIXEL_PNG = android.util.Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            android.util.Base64.DEFAULT
        )
    }
}
