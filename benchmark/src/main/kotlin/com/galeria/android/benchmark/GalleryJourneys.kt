package com.galeria.android.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

internal const val TARGET_PACKAGE = "com.galeria.android"

internal fun MacrobenchmarkScope.startGallery() {
    device.executeShellCommand("pm grant $TARGET_PACKAGE android.permission.READ_MEDIA_IMAGES")
    device.executeShellCommand("pm grant $TARGET_PACKAGE android.permission.READ_MEDIA_VIDEO")
    device.executeShellCommand("appops set --uid $TARGET_PACKAGE MANAGE_EXTERNAL_STORAGE allow")
    startActivityAndWait()
    device.wait(Until.hasObject(By.text("Pesquisar pastas")), 10_000)
}

internal fun MacrobenchmarkScope.scrollGalleryOnce() {
    swipeUpOnce()
}

internal fun MacrobenchmarkScope.openFirstAlbum() {
    val albumLabel = device.wait(
        Until.findObject(By.text(Pattern.compile(".+ \\(\\d+\\)$"))),
        20_000
    ) ?: error("Nenhum album com midias foi encontrado para o benchmark")
    generateSequence(albumLabel) { it.parent }
        .firstOrNull { it.isClickable }
        ?.click()
        ?: albumLabel.click()
    check(device.wait(Until.hasObject(By.text("Pesquisar nesta pasta")), 20_000)) {
        "A grade de midias nao abriu durante o benchmark"
    }
    device.waitForIdle()
}

internal fun MacrobenchmarkScope.scrollAlbumOnce() {
    swipeUpOnce()
}

private fun MacrobenchmarkScope.swipeUpOnce() {
    val width = device.displayWidth
    val height = device.displayHeight
    device.swipe(width / 2, height * 4 / 5, width / 2, height / 3, 12)
    device.waitForIdle()
}
