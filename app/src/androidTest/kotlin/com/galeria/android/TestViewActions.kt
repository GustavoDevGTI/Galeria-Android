package com.galeria.android

import android.view.View
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import org.hamcrest.Matcher

/**
 * Aciona semanticamente a linha clicável de menus e painéis.
 *
 * Os painéis laterais usam uma janela deslocada e escurecimento. No Android 16,
 * a injeção de coordenadas do Espresso pode ser bloqueada como toque encoberto,
 * embora a View esteja visível. Este acionamento continua exercitando o listener
 * real da interface sem depender das coordenadas da janela.
 */
internal fun clickClickableAncestor(): ViewAction = object : ViewAction {
    override fun getConstraints(): Matcher<View> = isDisplayed()

    override fun getDescription(): String = "acionar a linha clicável visível"

    override fun perform(uiController: UiController, view: View) {
        var clickable: View? = view
        while (clickable != null && !clickable.isClickable) {
            clickable = clickable.parent as? View
        }
        checkNotNull(clickable) { "Nenhuma View clicável encontrada para ${view.javaClass.simpleName}." }
        check(clickable.performClick()) { "A View clicável não processou a ação." }
        uiController.loopMainThreadUntilIdle()
    }
}

/** ActivityScenario's temporary lifecycle window can regain app focus before
 * Android 16 removes its untrusted task dim layer. Wait for the input dispatcher,
 * not a fixed sleep; otherwise real injected gestures are dropped by the system.
 * This does not disable touch protection or alter app gesture handling. */
internal fun awaitAndroidInputReady() {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val deadline = SystemClock.uptimeMillis() + 5_000L
    do {
        val input = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("dumpsys input"))
            .bufferedReader().use { it.readText() }
        val blocking = input.lineSequence().filter {
            it.contains("name=Dim Layer for - Task=") && it.contains("touchOcclusionMode=BLOCK_UNTRUSTED") &&
                !it.contains("NOT_VISIBLE") && !it.contains("TRUSTED_OVERLAY")
        }.toList()
        if (blocking.isEmpty()) return
        if (SystemClock.uptimeMillis() >= deadline) {
            throw AssertionError("Android ainda bloqueia os gestos com uma camada de transição: ${blocking.joinToString()}")
        }
        SystemClock.sleep(50L)
    } while (true)
}

/** Capture the actual Android windows while deterministic slow-I/O fixtures are held. */
internal fun captureLoadingFeedback(name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
    try {
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
    } finally { screenshot.recycle() }
}
