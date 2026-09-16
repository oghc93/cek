package com.lite.xraylite.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.lite.xraylite.BuildConfig
import com.lite.xraylite.R

class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        findViewById<Toolbar>(R.id.toolbarAbout).setNavigationOnClickListener { finish() }
        findViewById<TextView>(R.id.tvAppVersion).text =
            "v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
    }
}
