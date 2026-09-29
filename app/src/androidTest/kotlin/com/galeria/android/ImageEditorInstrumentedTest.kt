package com.galeria.android

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class ImageEditorInstrumentedTest {
    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO
    )

    @Test fun cropRotateTextAndFilterSaveCopyWithoutChangingOriginal() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "editor-${System.currentTimeMillis()}.png"
        val source = insertImage(context, name)
        val outputName = name.removeSuffix(".png") + "-editada.png"
        var output: Uri? = null
        try {
            ActivityScenario.launch<ImageEditActivity>(Intent(context, ImageEditActivity::class.java).apply {
                putExtra("uri", source.toString())
                putExtra("name", name)
                putExtra("mime", "image/png")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }).use {
                waitForText("Cortar")
                onView(withText("Cortar")).perform(click())
                onView(withText("Girar")).perform(click())
                onView(withText("Tamanho")).perform(click())
                onView(isAssignableFrom(EditText::class.java)).perform(replaceText("80"))
                onView(withText("Aplicar")).perform(click())
                onView(withText("Texto")).perform(click())
                onView(isAssignableFrom(EditText::class.java)).perform(replaceText("Teste"))
                onView(withText("Adicionar")).perform(click())
                onView(withText("Filtros")).perform(scrollTo(), click())
                onView(withText("P&B")).perform(click())
                onView(withText("Salvar")).perform(click())
            }
            val deadline = System.currentTimeMillis() + 10_000L
            while (output == null && System.currentTimeMillis() < deadline) {
                output = findImage(context, outputName)
                if (output == null) Thread.sleep(150L)
            }
            assertNotNull("A cópia editada não apareceu no MediaStore", output)
            context.contentResolver.openInputStream(requireNotNull(output)).use {
                val result = BitmapFactory.decodeStream(it)
                assertNotNull(result)
                assertTrue(result.width == 80 && result.height > 0)
            }
            assertNotNull(context.contentResolver.openInputStream(source)?.use(BitmapFactory::decodeStream))
        } finally {
            output?.let { context.contentResolver.delete(it, null, null) }
            context.contentResolver.delete(source, null, null)
        }
    }

    @Test fun editButtonOffersFocusedCropRotateAndCustomEditor() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "edit-menu-${System.nanoTime()}.png"
        val source = insertImage(context, name)
        try {
            ActivityScenario.launch<DetailActivity>(Intent(context, DetailActivity::class.java).apply {
                putExtra("uri", source.toString())
                putExtra("name", name)
                putExtra("mime", "image/png")
                putExtra("path", "Pictures/GaleriaEditorTest/")
                putExtra("album_key", "Pictures/GaleriaEditorTest/")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }).use {
                onView(withContentDescription("Editar")).perform(clickClickableAncestor())
                waitForText("Cortar")
                waitForText("Girar")
                onView(withText("Girar")).check(matches(isDisplayed()))
                onView(withText("Edição personalizada")).check(matches(isDisplayed()))
                onView(withText("Cortar")).perform(clickClickableAncestor())
                waitForText("Cortar imagem")
                waitForCropReady()
                onView(withText("Texto")).check(doesNotExist())

                pressBack()
                onView(withContentDescription("Editar")).perform(clickClickableAncestor())
                waitForText("Edição personalizada")
                onView(withText("Edição personalizada")).perform(clickClickableAncestor())
                waitForText("Editar imagem")
                onView(withText("Texto")).check(matches(isDisplayed()))

                pressBack()
                onView(withContentDescription("Editar")).perform(clickClickableAncestor())
                waitForText("Girar")
                onView(withText("Girar")).perform(clickClickableAncestor())
                val deadline = System.currentTimeMillis() + 10_000L
                var rotated = false
                while (!rotated && System.currentTimeMillis() < deadline) {
                    val dimensions = context.contentResolver.openInputStream(source)?.use {
                        BitmapFactory.decodeStream(it)?.let { bitmap -> bitmap.width to bitmap.height }
                    }
                    rotated = dimensions == (120 to 160)
                    if (!rotated) Thread.sleep(100L)
                }
                assertTrue("Girar deve alterar a orientação da foto temporária.", rotated)
            }
        } finally {
            context.contentResolver.delete(source, null, null)
        }
    }

    @Test fun focusedCropSavesCopyAndPreservesOriginal() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "quick-crop-${System.nanoTime()}.png"
        val source = insertImage(context, name)
        val outputName = name.removeSuffix(".png") + "-editada.png"
        var output: Uri? = null
        try {
            ActivityScenario.launch<ImageEditActivity>(Intent(context, ImageEditActivity::class.java).apply {
                putExtra("uri", source.toString())
                putExtra("name", name)
                putExtra("mime", "image/png")
                putExtra(ImageEditActivity.EXTRA_MODE, ImageEditActivity.MODE_CROP)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }).use {
                waitForText("Cortar imagem")
                waitForCropReady()
                onView(withText("Texto")).check(doesNotExist())
                onView(withText("Salvar")).perform(click())
                val deadline = System.currentTimeMillis() + 10_000L
                while (output == null && System.currentTimeMillis() < deadline) {
                    output = findImage(context, outputName)
                    if (output == null) Thread.sleep(100L)
                }
            }
            val cropped = context.contentResolver.openInputStream(requireNotNull(output)).use(BitmapFactory::decodeStream)
            val croppedBitmap = requireNotNull(cropped)
            assertTrue(croppedBitmap.width < 160 && croppedBitmap.height < 120)
            val original = context.contentResolver.openInputStream(source).use(BitmapFactory::decodeStream)
            assertTrue(original?.width == 160 && original?.height == 120)
        } finally {
            output?.let { context.contentResolver.delete(it, null, null) }
            context.contentResolver.delete(source, null, null)
        }
    }

    private fun waitForCropReady() {
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            try {
                onView(withContentDescription("Imagem pronta para recorte")).check(matches(isDisplayed()))
                return
            } catch (_: Throwable) {
                Thread.sleep(100L)
            }
        }
        throw AssertionError("A foto não ficou pronta para recorte")
    }

    private fun insertImage(context: Context, name: String): Uri {
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/GaleriaEditorTest/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        resolver.openOutputStream(uri)?.use { output ->
            Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.YELLOW) }
                .compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun findImage(context: Context, name: String): Uri? = context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Images.Media._ID),
        "${MediaStore.Images.Media.DISPLAY_NAME}=?",
        arrayOf(name),
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)) else null
    }

    private fun waitForText(text: String) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            try {
                onView(withText(text)).check(matches(isDisplayed()))
                return
            } catch (_: Throwable) {
                Thread.sleep(100L)
            }
        }
        throw AssertionError("A opção $text não apareceu")
    }
}
