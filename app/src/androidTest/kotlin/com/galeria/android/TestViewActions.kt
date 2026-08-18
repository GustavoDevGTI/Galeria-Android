package com.galeria.android

import android.view.View
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
