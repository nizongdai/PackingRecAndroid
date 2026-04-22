package ltd.zejin.packingrec

import android.content.ContentValues
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.util.concurrent.Executors

class LocalVideosActivity : AppCompatActivity() {
    private val selected = mutableSetOf<String>()
    private val allVideos = mutableListOf<File>()
    private val shownVideos = mutableListOf<File>()
    private lateinit var adapter: VideoAdapter
    private lateinit var searchInput: EditText
    private var currentQuery: String = ""
    private val ioExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_local_videos)

        val listView = findViewById<ListView>(R.id.videoList)
        val emptyView = findViewById<android.view.View>(R.id.emptyView)
        val backButton = findViewById<android.view.View>(R.id.backButton)
        val deleteButton = findViewById<android.view.View>(R.id.deleteButton)
        val downloadButton = findViewById<android.view.View>(R.id.downloadButton)
        val uploadButton = findViewById<android.view.View>(R.id.uploadButton)
        searchInput = findViewById(R.id.searchInput)
        listView.emptyView = emptyView
        backButton.setOnClickListener { finish() }
        deleteButton.setOnClickListener { deleteSelected() }
        downloadButton.setOnClickListener { downloadSelected() }
        uploadButton.setOnClickListener { uploadSelected() }

        adapter = VideoAdapter()
        listView.adapter = adapter
        allVideos.clear()
        allVideos.addAll(loadVideoFiles())
        applyFilter("")

        listView.setOnItemClickListener { _, _, position, _ ->
            toggleSelection(position)
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString().orEmpty()
                applyFilter(query)
            }
        })
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

    private fun toggleSelection(position: Int) {
        val file = shownVideos.getOrNull(position) ?: return
        val key = file.absolutePath
        if (selected.contains(key)) {
            selected.remove(key)
        } else {
            selected.add(key)
        }
        adapter.notifyDataSetChanged()
    }

    private fun deleteSelected() {
        if (selected.isEmpty()) {
            Toast.makeText(this, getString(R.string.delete_none_selected), Toast.LENGTH_SHORT).show()
            return
        }
        val toDelete = allVideos.filter { selected.contains(it.absolutePath) }
        var removed = 0
        toDelete.forEach { file ->
            if (file.exists() && file.delete()) {
                removed += 1
            }
        }
        selected.clear()
        refreshList()
        Toast.makeText(this, getString(R.string.delete_done, removed), Toast.LENGTH_SHORT).show()
    }

    private fun downloadSelected() {
        if (selected.isEmpty()) {
            Toast.makeText(this, getString(R.string.download_none_selected), Toast.LENGTH_SHORT).show()
            return
        }
        val toSave = allVideos.filter { selected.contains(it.absolutePath) }
        ioExecutor.execute {
            val saved = toSave.count { copyToGallery(it) }
            runOnUiThread {
                if (saved == 0) {
                    Toast.makeText(this, getString(R.string.download_failed), Toast.LENGTH_SHORT).show()
                } else {
                    selected.clear()
                    refreshList()
                    Toast.makeText(this, getString(R.string.download_done, saved), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun copyToGallery(file: File): Boolean {
        return try {
            val relativePath = "Movies/${AppSettings(this).load().outputSubdir}"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            resolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input ->
                    input.copyTo(output)
                }
            }
            val doneValues = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            resolver.update(uri, doneValues, null, null)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun uploadSelected() {
        if (selected.isEmpty()) {
            Toast.makeText(this, getString(R.string.upload_none_selected), Toast.LENGTH_SHORT).show()
            return
        }
        val toUpload = allVideos.filter { selected.contains(it.absolutePath) }
        val workManager = WorkManager.getInstance(this)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        toUpload.forEach { file ->
            val data = workDataOf(
                FtpUploadWorker.KEY_FILE_PATH to file.absolutePath,
                FtpUploadWorker.KEY_FOLDER_NAME to file.nameWithoutExtension
            )
            val request = OneTimeWorkRequestBuilder<FtpUploadWorker>()
                .setInputData(data)
                .setConstraints(constraints)
                .build()
            workManager.enqueueUniqueWork(
                "ftp_upload_queue",
                ExistingWorkPolicy.APPEND,
                request
            )
        }
        selected.clear()
        refreshList()
        Toast.makeText(this, getString(R.string.upload_done, toUpload.size), Toast.LENGTH_SHORT).show()
    }

    private fun refreshList() {
        allVideos.clear()
        allVideos.addAll(loadVideoFiles())
        applyFilter(currentQuery)
    }

    private fun applyFilter(query: String) {
        currentQuery = query
        val needle = query.trim()
        val visible = if (needle.isBlank()) {
            allVideos
        } else {
            allVideos.filter { it.name.contains(needle, ignoreCase = true) }
        }
        val allPaths = allVideos.map { it.absolutePath }.toSet()
        selected.retainAll(allPaths)
        shownVideos.clear()
        shownVideos.addAll(visible)
        adapter.notifyDataSetChanged()
    }

    private inner class VideoAdapter : android.widget.BaseAdapter() {
        override fun getCount(): Int = shownVideos.size

        override fun getItem(position: Int): File = shownVideos[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_local_video, parent, false)
            val checkBox = view.findViewById<CheckBox>(R.id.videoCheckBox)
            val nameView = view.findViewById<TextView>(R.id.videoName)
            val file = getItem(position)
            val key = file.absolutePath
            nameView.text = file.name
            checkBox.setOnCheckedChangeListener(null)
            checkBox.isChecked = selected.contains(key)
            checkBox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selected.add(key)
                } else {
                    selected.remove(key)
                }
            }
            nameView.setOnClickListener {
                val uri = FileProvider.getUriForFile(
                    this@LocalVideosActivity,
                    "${packageName}.fileprovider",
                    file
                )
                openVideo(uri.toString())
            }
            return view
        }
    }
}
