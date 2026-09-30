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

    @Test fun brushPreservesEachStrokeColorAndWidthAndTextIsInline() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = insertImage(context, "brush-${System.nanoTime()}.png")
        try {
            ActivityScenario.launch<ImageEditActivity>(Intent(context, ImageEditActivity::class.java).apply {
                putExtra("uri", source.toString()); putExtra("name", "brush.png"); putExtra("mime", "image/png")
            }).use { scenario ->
                val deadline = System.currentTimeMillis() + 10000
                var ready = false
                while (!ready && System.currentTimeMillis() < deadline) {
                    scenario.onActivity { ready = it.window.decorView.findViewWithTag<android.view.View>("editor_canvas").contentDescription == "Imagem pronta para edição" }
                    if (!ready) Thread.sleep(100)
                }
                assertTrue(ready)
                onView(withContentDescription("Pincel")).perform(click())
                fun draw(colorX: Float, lineY: Float) = scenario.onActivity { activity ->
                    val canvas = activity.window.decorView.findViewWithTag<android.view.View>("editor_canvas")
                    val spectrum = descendants(activity.window.decorView).filterIsInstance<EditorColorSpectrum>().first()
                    fun event(view: android.view.View, action: Int, x: Float, y: Float) {
                        val now = android.os.SystemClock.uptimeMillis()
                        android.view.MotionEvent.obtain(now, now, action, x, y, 0).also { view.dispatchTouchEvent(it); it.recycle() }
                    }
                    event(spectrum, android.view.MotionEvent.ACTION_DOWN, spectrum.width * colorX, spectrum.height * 0.5f)
                    event(spectrum, android.view.MotionEvent.ACTION_UP, spectrum.width * colorX, spectrum.height * 0.5f)
                    val x = canvas.width * 0.3f
                    val y = canvas.height * lineY
                    event(canvas, android.view.MotionEvent.ACTION_DOWN, x, y)
                    event(canvas, android.view.MotionEvent.ACTION_MOVE, canvas.width * 0.7f, y)
                    event(canvas, android.view.MotionEvent.ACTION_UP, canvas.width * 0.7f, y)
                }
                draw(0f, 0.45f)
                onView(withContentDescription("Pincel")).perform(click(), click())
                draw(2f / 3f, 0.55f)
                scenario.onActivity { activity ->
                    val canvas = activity.window.decorView.findViewWithTag<android.view.View>("editor_canvas")
                    val bitmap = canvas.javaClass.getDeclaredMethod("renderEditedBitmap").apply { isAccessible = true }.invoke(canvas) as Bitmap
                    var red = 0; var blue = 0
                    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                        val color = bitmap.getPixel(x, y)
                        if (Color.red(color) > 180 && Color.green(color) < 80 && Color.blue(color) < 80) red++
                        if (Color.blue(color) > 180 && Color.red(color) < 80) blue++
                    }
                    bitmap.recycle()
                    assertTrue("Traço anterior deve manter o vermelho", red > 0)
                    assertTrue("Segundo traço deve ser azul e mais largo", blue > red)
                }
                onView(withContentDescription("Texto")).perform(click())
                onView(isAssignableFrom(EditText::class.java)).perform(replaceText("Texto na foto"), androidx.test.espresso.action.ViewActions.closeSoftKeyboard())
                scenario.onActivity { activity ->
                    val input = activity.window.decorView.findViewWithTag<EditText>("editor_inline_text")
                    assertNotNull(input)
                    assertTrue(input.parent === activity.window.decorView.findViewWithTag<android.view.View>("editor_canvas").parent)
                }
                val screenshot = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                java.io.File(context.getExternalFilesDir(null), "editor-qa.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                screenshot.recycle()
            }
        } finally { context.contentResolver.delete(source, null, null) }
    }

    private fun descendants(view: android.view.View): List<android.view.View> = listOf(view) +
        if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

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
                waitForText("Editar imagem")
                onView(withContentDescription("Cortar")).perform(click())
                onView(withContentDescription("Girar")).perform(click())
                onView(withContentDescription("Redimensionar imagem")).perform(scrollTo(), click())
                onView(isAssignableFrom(EditText::class.java)).perform(replaceText("80"))
                onView(withText("Aplicar")).perform(click())
                onView(withContentDescription("Texto")).perform(scrollTo(), click())
                onView(isAssignableFrom(EditText::class.java)).perform(replaceText("Teste"))
                onView(isAssignableFrom(EditText::class.java)).perform(androidx.test.espresso.action.ViewActions.closeSoftKeyboard())
                onView(withContentDescription("Texto")).perform(click())
                onView(withContentDescription("Filtros")).perform(scrollTo(), click())
                onView(withContentDescription("P&B")).perform(click())
                onView(withContentDescription("Salvar cópia")).perform(click())
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
                waitForMenu()
                onView(withContentDescription("Girar")).check(matches(isDisplayed()))
                onView(withContentDescription("Edição personalizada")).check(matches(isDisplayed()))
                onView(withContentDescription("Cortar")).perform(clickClickableAncestor())
                waitForText("Cortar imagem")
                waitForCropReady()
                onView(withContentDescription("Texto")).check(doesNotExist())

                pressBack()
                onView(withContentDescription("Editar")).perform(clickClickableAncestor())
                waitForMenu()
                onView(withContentDescription("Edição personalizada")).perform(clickClickableAncestor())
                waitForText("Editar imagem")
                onView(withContentDescription("Texto")).check(matches(isDisplayed()))

                pressBack()
                onView(withContentDescription("Editar")).perform(clickClickableAncestor())
                waitForMenu()
                onView(withContentDescription("Girar")).perform(clickClickableAncestor())
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
                onView(withContentDescription("Texto")).check(doesNotExist())
                onView(withContentDescription("Imagem pronta para recorte")).perform(cropCorner())
                onView(withContentDescription("Salvar cópia")).perform(click())
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

    private fun waitForMenu() {
        val deadline = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < deadline) {
            try {
                onView(withContentDescription("Edição personalizada"))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check(matches(isDisplayed()))
                return
            } catch (_: Throwable) { Thread.sleep(100) }
        }
        throw AssertionError("Submenu de edição não ficou visível")
    }

    private fun cropCorner() = object : androidx.test.espresso.ViewAction {
        override fun getConstraints() = isDisplayed()
        override fun getDescription() = "Arrastar canto superior esquerdo do recorte"
        override fun perform(controller: androidx.test.espresso.UiController, view: android.view.View) {
            val scale = minOf((view.width - Ui.dp(view.context, 40)) / 160f, (view.height - Ui.dp(view.context, 40)) / 120f)
            val x = (view.width - 160 * scale) / 2f
            val y = (view.height - 120 * scale) / 2f
            val down = android.os.SystemClock.uptimeMillis()
            listOf(Triple(android.view.MotionEvent.ACTION_DOWN, x, y), Triple(android.view.MotionEvent.ACTION_MOVE, x + 24 * scale, y + 20 * scale), Triple(android.view.MotionEvent.ACTION_UP, x + 24 * scale, y + 20 * scale)).forEach { (action, px, py) ->
                val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, px, py, 0)
                view.dispatchTouchEvent(event); event.recycle()
            }
            controller.loopMainThreadUntilIdle()
        }
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
