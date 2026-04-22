package ltd.zejin.packingrec

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity

class LocalVideoPlayerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_local_video_player)

        val videoView = findViewById<VideoView>(R.id.videoView)
        val backButton = findViewById<Button>(R.id.playerBackButton)

        val uriText = intent.getStringExtra(EXTRA_VIDEO_URI).orEmpty()
        if (uriText.isNotBlank()) {
            val controller = MediaController(this)
            controller.setAnchorView(videoView)
            videoView.setMediaController(controller)
            videoView.setOnPreparedListener { mediaPlayer ->
                mediaPlayer.setVideoScalingMode(android.media.MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                val videoWidth = mediaPlayer.videoWidth
                val videoHeight = mediaPlayer.videoHeight
                if (videoWidth > 0 && videoHeight > 0) {
                    videoView.post {
                        val parent = videoView.parent as View
                        val viewWidth = parent.width
                        val viewHeight = parent.height
                        if (viewWidth > 0 && viewHeight > 0) {
                            val aspect = videoWidth.toFloat() / videoHeight.toFloat()
                            var targetWidth = viewWidth
                            var targetHeight = (viewWidth / aspect).toInt()
                            if (targetHeight > viewHeight) {
                                targetHeight = viewHeight
                                targetWidth = (viewHeight * aspect).toInt()
                            }
                            val params = FrameLayout.LayoutParams(targetWidth, targetHeight)
                            params.gravity = android.view.Gravity.CENTER
                            videoView.layoutParams = params
                        }
                    }
                }
            }
            videoView.setVideoURI(Uri.parse(uriText))
            videoView.start()
        }

        backButton.setOnClickListener { finish() }
    }

    companion object {
        const val EXTRA_VIDEO_URI = "video_uri"
    }
}
