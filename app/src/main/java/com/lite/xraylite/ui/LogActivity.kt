package com.lite.xraylite.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lite.xraylite.R
import com.lite.xraylite.util.Logger

class LogActivity : AppCompatActivity() {

    private lateinit var adapter: LogAdapter
    private lateinit var rv: RecyclerView
    private val listener: () -> Unit = { refresh(scrollToEnd = true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        val toolbar = findViewById<Toolbar>(R.id.toolbarLog)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.actionCopyLogs -> {
                    copyAllLogs()
                    true
                }
                R.id.actionClearLogs -> {
                    Logger.clear()
                    true
                }
                else -> false
            }
        }

        rv = findViewById(R.id.rvLogs)
        adapter = LogAdapter()
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        refresh(scrollToEnd = true)
    }

    override fun onStart() {
        super.onStart()
        Logger.addListener(listener)
    }

    override fun onStop() {
        Logger.removeListener(listener)
        super.onStop()
    }

    private fun refresh(scrollToEnd: Boolean) {
        val all = Logger.all()
        adapter.submit(all)
        if (scrollToEnd && all.isNotEmpty()) rv.scrollToPosition(all.size - 1)
    }

    private fun copyAllLogs() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("OG Tunnel logs", Logger.allFormatted()))
        Toast.makeText(this, "Log disalin ke clipboard", Toast.LENGTH_SHORT).show()
    }
}
