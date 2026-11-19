package com.galeria.android

import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView

/**
 * Search by the text inside the photos from the main screen: while a word is typed, a row under the
 * search box offers "Procurar no texto das fotos"; it, or the keyboard's search key, opens the results.
 */
internal fun MainActivity.createTextSearchRow(): TextView = TextView(this).apply {
    tag = "main_text_search"
    textSize = 14f
    gravity = Gravity.CENTER_VERTICAL
    setTextColor(Ui.text(this@createTextSearchRow))
    background = Ui.rounded(Ui.search(this@createTextSearchRow), 18, this@createTextSearchRow)
    setCompoundDrawablesRelativeWithIntrinsicBounds(getDrawable(R.drawable.ic_text_recognition)?.mutate()?.apply {
        setTint(Ui.accent(this@createTextSearchRow))
    }, null, null, null)
    compoundDrawablePadding = Ui.dp(this@createTextSearchRow, 10)
    Ui.setPadding(this, 14, 10, 14, 10)
    visibility = View.GONE
    isClickable = true
    isFocusable = true
    setOnClickListener { openTextSearch(searchInput.text.toString()) }
}

internal fun MainActivity.updateTextSearchRow(query: String) {
    val row = textSearchRow ?: return
    val show = MediaTextRules.terms(query).isNotEmpty() && !adapter.isSelectionMode()
    row.visibility = if (show) View.VISIBLE else View.GONE
    if (show) row.text = getString(R.string.search_text_in_photos, query.trim())
}

internal fun MainActivity.installTextSearchKey() {
    searchInput.imeOptions = EditorInfo.IME_ACTION_SEARCH
    searchInput.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_SEARCH && MediaTextRules.terms(searchInput.text.toString()).isNotEmpty()) {
            openTextSearch(searchInput.text.toString())
            true
        } else false
    }
}

/** "Resultados da pesquisa": the photos whose text has every typed word. */
internal fun MainActivity.openTextSearch(query: String) {
    if (MediaTextRules.terms(query).isEmpty()) return
    (getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(searchInput.windowToken, 0)
    startActivity(Intent(this, AlbumMediaActivity::class.java).apply {
        putExtra("album_key", VirtualAlbumRules.searchKey(query))
        putExtra("album_name", getString(R.string.search_results_title))
    })
}
