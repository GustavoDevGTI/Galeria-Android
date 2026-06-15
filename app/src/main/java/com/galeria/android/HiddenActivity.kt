package com.galeria.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextView

class HiddenActivity : Activity() {
    private lateinit var adapter: HiddenFileAdapter
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildLayout()
    }

    override fun onResume() {
        super.onResume()
        loadFiles()
    }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }

        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            Ui.setPadding(this, 10, 8, 10, 8)
        }
        val back = Ui.title(this, "Voltar", 16).apply {
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }
        bar.addView(back, LinearLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 44)))

        val title = Ui.title(this, "Ocultos", 22)
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)

        val content = FrameLayout(this)
        val grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = Ui.dp(this@HiddenActivity, 132)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = Ui.dp(this@HiddenActivity, 2)
            verticalSpacing = Ui.dp(this@HiddenActivity, 2)
            setPadding(
                Ui.dp(this@HiddenActivity, 6),
                Ui.dp(this@HiddenActivity, 6),
                Ui.dp(this@HiddenActivity, 6),
                Ui.dp(this@HiddenActivity, 16)
            )
            clipToPadding = false
        }
        adapter = HiddenFileAdapter(this)
        grid.adapter = adapter
        grid.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val file = adapter.getItem(position)
            val intent = Intent(this, FileDetailActivity::class.java).apply {
                putExtra("path", file.absolutePath)
            }
            startActivity(intent)
        }
        content.addView(grid)

        emptyView = Ui.label(this, "Nenhum item oculto.")
        content.addView(
            emptyView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun loadFiles() {
        val dir = MediaActions.hiddenDir(this)
        val files = if (dir.exists()) dir.listFiles() else null
        adapter.submit(files)
        emptyView.visibility = if (adapter.count == 0) View.VISIBLE else View.GONE
    }
}
