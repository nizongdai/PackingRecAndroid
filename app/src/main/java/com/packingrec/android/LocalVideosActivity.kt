package com.packingrec.android

import android.os.Bundle
import android.os.Environment
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

class LocalVideosActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_local_videos)

        val listView = findViewById<ListView>(R.id.videoList)
        val emptyView = findViewById<android.view.View>(R.id.emptyView)
        val backButton = findViewById<android.view.View>(R.id.backButton)
        listView.emptyView = emptyView
        backButton.setOnClickListener { finish() }

        val videos = loadVideoFiles()
        val items = videos.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            val file = videos[position]
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            openVideo(uri.toString())
        }
    }

    private fun loadVideoFiles(): List<File> {
        val settings = AppSettings(this).load()
        val baseDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        val outputDir = File(baseDir, settings.outputSubdir)
        val files = outputDir.listFiles { file ->
            file.isFile && file.extension.equals("mp4", ignoreCase = true)
        } ?: emptyArray()
        return files.sortedByDescending { it.lastModified() }
    }

    private fun openVideo(uri: String) {
        val intent = android.content.Intent(this, LocalVideoPlayerActivity::class.java).apply {
            putExtra(LocalVideoPlayerActivity.EXTRA_VIDEO_URI, uri)
        }
        startActivity(intent)
    }
}
