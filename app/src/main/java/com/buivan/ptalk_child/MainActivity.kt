package com.buivan.ptalk_child

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.buivan.ptalk_child.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val audioRecorder by lazy { AudioRecorder(this) }
    private val audioPlayer by lazy { AudioPlayer() }           // ← bỏ context
    private val apiService by lazy { ApiService(this) }         // ← thêm context

    private val characterAnimator by lazy { CharacterAnimator(binding.ivCharacter) }
    private val waveformView by lazy { binding.waveformView }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        requestMicPermission()
        observeState()
        setupButtons()
        characterAnimator.playIdle()
        checkServerHealth()             // ← kiểm tra server khi mở app
        
        showIntroAnimation()
    }

    // ─── Intro Animation ────────────────────────────────────────────────
    private fun showIntroAnimation() {
        binding.layoutIntro.visibility = View.VISIBLE
        binding.ivIntroLogo.alpha = 0f
        
        // 1. Fade in logo
        binding.ivIntroLogo.animate()
            .alpha(1f)
            .setDuration(1500)
            .withEndAction {
                // 2. Wait 1s then fade out the whole overlay
                binding.layoutIntro.postDelayed({
                    binding.layoutIntro.animate()
                        .alpha(0f)
                        .setDuration(800)
                        .withEndAction {
                            binding.layoutIntro.visibility = View.GONE
                        }
                        .start()
                }, 1000)
            }
            .start()
    }

    // ─── Kiểm tra server khi khởi động ───────────────────────────────────
    private fun checkServerHealth() {
        lifecycleScope.launch {
            val healthy = apiService.isServerHealthy()
            if (!healthy) {
                binding.tvStatus.text = "⚠️ Server đang offline"
                binding.btnHoldToTalk.isEnabled = false
                Toast.makeText(
                    this@MainActivity,
                    "Không kết nối được server, kiểm tra mạng nhé!",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ─── Observe state ────────────────────────────────────────────────────
    private fun observeState() {
        viewModel.statusText.observe(this) { text ->
            binding.tvStatus.text = text
        }

        viewModel.state.observe(this) { state ->
            when (state) {
                AppState.IDLE -> {
                    binding.btnHoldToTalk.isEnabled = true
                    binding.btnHoldToTalk.alpha = 1f
                    binding.btnCancel.visibility = View.GONE
                    characterAnimator.playIdle()
                    waveformView.setStateIdle()
                }
                AppState.RECORDING -> {
                    binding.btnHoldToTalk.isEnabled = true
                    binding.btnHoldToTalk.alpha = 0.75f
                    binding.btnCancel.visibility = View.GONE
                    characterAnimator.playRecording()
                    waveformView.setStateRecording()
                }
                AppState.UPLOADING -> {
                    binding.btnHoldToTalk.isEnabled = false
                    binding.btnHoldToTalk.alpha = 0.5f
                    binding.btnCancel.visibility = View.GONE
                    characterAnimator.playUploading()
                    waveformView.setStateUploading()
                }
                AppState.PLAYING -> {
                    binding.btnHoldToTalk.isEnabled = false
                    binding.btnHoldToTalk.alpha = 0.5f
                    binding.btnCancel.visibility = View.VISIBLE
                    characterAnimator.playPlaying()
                    waveformView.setStatePlaying()
                }
                AppState.ERROR -> {
                    binding.btnHoldToTalk.isEnabled = true
                    binding.btnHoldToTalk.alpha = 1f
                    binding.btnCancel.visibility = View.GONE
                    characterAnimator.playError()
                    waveformView.setStateError()
                    Toast.makeText(
                        this,
                        viewModel.statusText.value ?: "Có lỗi xảy ra",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // ─── Setup nút bấm ───────────────────────────────────────────────────
    private fun setupButtons() {
        binding.btnHoldToTalk.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        audioRecorder.start()
                        viewModel.onStartRecording()
                    } else {
                        requestMicPermission()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val audioFile = audioRecorder.stop()
                    if (audioFile != null && audioFile.length() > 0) {
                        viewModel.onStopRecording()
                        sendAudioToServer(audioFile)
                    } else {
                        viewModel.onError("Ghi âm thất bại, thử lại!")
                    }
                    true
                }
                else -> false
            }
        }

        binding.btnCancel.setOnClickListener {
            audioPlayer.stop()
            viewModel.onCancelPlayback()
        }
    }

    // ─── Gửi audio lên server ─────────────────────────────────────────────
    private fun sendAudioToServer(audioFile: File) {
        lifecycleScope.launch {                         // ← dùng coroutine thay runOnUiThread
            apiService.sendAudio(audioFile, object : ApiService.AudioResponseCallback {
                override fun onSuccess(audioFile: File) {
                    runOnUiThread {
                        viewModel.onStartPlaying()
                        audioPlayer.play(
                            audioFile = audioFile,      // ← truyền File thay vì ByteArray
                            onComplete = {
                                runOnUiThread { viewModel.onFinishPlaying() }
                            },
                            onError = { errorMsg ->
                                runOnUiThread { viewModel.onError(errorMsg) }
                            }
                        )
                    }
                }

                override fun onError(errorMessage: String) {
                    runOnUiThread { viewModel.onError(errorMessage) }
                }
            })
        }
    }

    // ─── Xin quyền mic ───────────────────────────────────────────────────
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted) {
                Toast.makeText(this, "App cần quyền micro để hoạt động!", Toast.LENGTH_LONG).show()
            }
        }

    private fun requestMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audioPlayer.stop()
        audioRecorder.stop()
        characterAnimator.stopCurrent()
    }
}




//package com.buivan.ptalk_child
//
//import android.Manifest
//import android.content.pm.PackageManager
//import android.os.Bundle
//import android.view.MotionEvent
//import android.view.View
//import android.widget.Toast
//import androidx.activity.enableEdgeToEdge
//import androidx.activity.result.contract.ActivityResultContracts
//import androidx.activity.viewModels
//import androidx.appcompat.app.AppCompatActivity
//import androidx.core.content.ContextCompat
//import androidx.core.view.ViewCompat
//import androidx.core.view.WindowInsetsCompat
//import com.buivan.ptalk_child.databinding.ActivityMainBinding
//
//class MainActivity : AppCompatActivity() {
//
//    private lateinit var binding: ActivityMainBinding
//    private val viewModel: MainViewModel by viewModels()
//
//    private val audioRecorder by lazy { AudioRecorder(this) }
//    private val audioPlayer by lazy { AudioPlayer(this) }
//    private val apiService by lazy { ApiService() }
//
//    private val characterAnimator by lazy { CharacterAnimator(binding.ivCharacter) }
//
//    private val waveformView by lazy { binding.waveformView }
//
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        enableEdgeToEdge()
//        binding = ActivityMainBinding.inflate(layoutInflater)
//        setContentView(binding.root)
//
//        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
//            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
//            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
//            insets
//        }
//
//        requestMicPermission()
//        observeState()
//        setupButtons()
//
//        characterAnimator.playIdle()
//    }
//
//    // ─── Observe state ────────────────────────────────────────────────────
//    private fun observeState() {
//        viewModel.statusText.observe(this) { text ->
//            binding.tvStatus.text = text
//        }
//
//        viewModel.state.observe(this) { state ->
//            when (state) {
//                AppState.IDLE -> {
//                    binding.btnHoldToTalk.isEnabled = true
//                    binding.btnHoldToTalk.alpha = 1f
//                    binding.btnCancel.visibility = View.GONE
//                    characterAnimator.playIdle()
//                    waveformView.setStateIdle()
//                }
//                AppState.RECORDING -> {
//                    binding.btnHoldToTalk.isEnabled = true
//                    binding.btnHoldToTalk.alpha = 0.75f
//                    binding.btnCancel.visibility = View.GONE
//                    characterAnimator.playRecording()
//                    waveformView.setStateRecording()
//                }
//                AppState.UPLOADING -> {
//                    binding.btnHoldToTalk.isEnabled = false
//                    binding.btnHoldToTalk.alpha = 0.5f
//                    binding.btnCancel.visibility = View.GONE
//                    characterAnimator.playUploading()
//                    waveformView.setStateUploading()
//                }
//                AppState.PLAYING -> {
//                    binding.btnHoldToTalk.isEnabled = false
//                    binding.btnHoldToTalk.alpha = 0.5f
//                    binding.btnCancel.visibility = View.VISIBLE
//                    characterAnimator.playPlaying()
//                    waveformView.setStatePlaying()
//                }
//                AppState.ERROR -> {
//                    binding.btnHoldToTalk.isEnabled = true
//                    binding.btnHoldToTalk.alpha = 1f
//                    binding.btnCancel.visibility = View.GONE
//                    characterAnimator.playError()
//                    waveformView.setStateError()
//                    Toast.makeText(
//                        this,
//                        viewModel.statusText.value ?: "Có lỗi xảy ra",
//                        Toast.LENGTH_SHORT
//                    ).show()
//                }
//            }
//        }
//    }
//
//    // ─── Setup nút bấm ───────────────────────────────────────────────────
//    private fun setupButtons() {
//        binding.btnHoldToTalk.setOnTouchListener { _, event ->
//            when (event.action) {
//                MotionEvent.ACTION_DOWN -> {
//                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
//                        == PackageManager.PERMISSION_GRANTED
//                    ) {
//                        audioRecorder.start()
//                        viewModel.onStartRecording()
//                    } else {
//                        requestMicPermission()
//                    }
//                    true
//                }
//                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
//                    val audioFile = audioRecorder.stop()
//                    if (audioFile != null && audioFile.length() > 0) {
//                        viewModel.onStopRecording()
//                        sendAudioToServer(audioFile)
//                    } else {
//                        viewModel.onError("Ghi âm thất bại, thử lại!")
//                    }
//                    true
//                }
//                else -> false
//            }
//        }
//
//        binding.btnCancel.setOnClickListener {
//            audioPlayer.stop()
//            viewModel.onCancelPlayback()
//        }
//    }
//
//    // ─── Gửi audio lên server ─────────────────────────────────────────────
//    private fun sendAudioToServer(audioFile: java.io.File) {
//        apiService.sendAudio(audioFile, object : ApiService.AudioResponseCallback {
//            override fun onSuccess(audioBytes: ByteArray) {
//                // Chạy trên main thread để update UI
//                runOnUiThread {
//                    viewModel.onStartPlaying()
//                    audioPlayer.play(
//                        audioBytes = audioBytes,
//                        onComplete = {
//                            runOnUiThread { viewModel.onFinishPlaying() }
//                        },
//                        onError = { errorMsg ->
//                            runOnUiThread { viewModel.onError(errorMsg) }
//                        }
//                    )
//                }
//            }
//
//            override fun onError(errorMessage: String) {
//                runOnUiThread { viewModel.onError(errorMessage) }
//            }
//        })
//    }
//
//    // ─── Xin quyền mic ───────────────────────────────────────────────────
//    private val requestPermissionLauncher =
//        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
//            if (!isGranted) {
//                Toast.makeText(this, "App cần quyền micro để hoạt động!", Toast.LENGTH_LONG).show()
//            }
//        }
//
//    private fun requestMicPermission() {
//        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
//            != PackageManager.PERMISSION_GRANTED
//        ) {
//            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
//        }
//    }
//
//    override fun onDestroy() {
//        super.onDestroy()
//        audioPlayer.stop()
//        audioRecorder.stop()
//        characterAnimator.stopCurrent()
//    }
//}






//package com.buivan.ptalk_child
//
//import android.os.Bundle
//import android.view.MotionEvent
//import android.view.View
//import android.widget.Toast
//import androidx.activity.enableEdgeToEdge
//import androidx.activity.viewModels
//import androidx.appcompat.app.AppCompatActivity
//import androidx.core.view.ViewCompat
//import androidx.core.view.WindowInsetsCompat
//import com.buivan.ptalk_child.databinding.ActivityMainBinding
//import android.Manifest
//import android.content.pm.PackageManager
//import androidx.activity.result.contract.ActivityResultContracts
//import androidx.core.content.ContextCompat
//
//
//class MainActivity : AppCompatActivity() {
//
//    private lateinit var binding: ActivityMainBinding
//    private val viewModel: MainViewModel by viewModels()
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        enableEdgeToEdge()
//        binding = ActivityMainBinding.inflate(layoutInflater)
//        setContentView(binding.root)
//
//        // Giữ padding đúng cho edge-to-edge
//        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
//            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
//            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
//            insets
//        }
//
//        requestMicPermission()
//        observeState()
//        setupButtons()
//
//        // ── TEST: xóa sau khi confirm state machine chạy đúng ──
//        // binding.root.postDelayed({ viewModel.onStartPlaying() }, 3000)
//    }
//
//    // ─── Observe state, cập nhật UI tại 1 chỗ duy nhất ──────────────────
//    private fun observeState() {
//
//        viewModel.statusText.observe(this) { text ->
//            binding.tvStatus.text = text
//        }
//
//        viewModel.state.observe(this) { state ->
//            when (state) {
//                AppState.IDLE -> {
//                    binding.btnHoldToTalk.isEnabled = true
//                    binding.btnHoldToTalk.alpha = 1f
//                    binding.btnCancel.visibility = View.GONE
//                }
//                AppState.RECORDING -> {
//                    binding.btnHoldToTalk.isEnabled = true
//                    binding.btnHoldToTalk.alpha = 0.75f
//                    binding.btnCancel.visibility = View.GONE
//                }
//                AppState.UPLOADING -> {
//                    binding.btnHoldToTalk.isEnabled = false
//                    binding.btnHoldToTalk.alpha = 0.5f
//                    binding.btnCancel.visibility = View.GONE
//                }
//                AppState.PLAYING -> {
//                    binding.btnHoldToTalk.isEnabled = false
//                    binding.btnHoldToTalk.alpha = 0.5f
//                    binding.btnCancel.visibility = View.VISIBLE
//                }
//                AppState.ERROR -> {
//                    binding.btnHoldToTalk.isEnabled = true
//                    binding.btnHoldToTalk.alpha = 1f
//                    binding.btnCancel.visibility = View.GONE
//                    Toast.makeText(
//                        this,
//                        viewModel.statusText.value ?: "Có lỗi xảy ra",
//                        Toast.LENGTH_SHORT
//                    ).show()
//                }
//            }
//        }
//    }
//
//    // ─── Setup nút bấm ───────────────────────────────────────────────────
//    private val audioRecorder by lazy { AudioRecorder(this) }
//
//    private fun setupButtons() {
//
//        binding.btnHoldToTalk.setOnTouchListener { _, event ->
//            when (event.action) {
//                MotionEvent.ACTION_DOWN -> {
//                    // Kiểm tra quyền trước khi ghi
//                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
//                        == PackageManager.PERMISSION_GRANTED
//                    ) {
//                        audioRecorder.start()
//                        viewModel.onStartRecording()
//                    } else {
//                        requestMicPermission()
//                    }
//                    true
//                }
//                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
//                    val audioFile = audioRecorder.stop()
//                    if (audioFile != null) {
//                        viewModel.onStopRecording()
//                        // TODO bước sau: gửi audioFile lên server
//                        android.util.Log.d("PTalk", "File ghi xong: ${audioFile.absolutePath}, size: ${audioFile.length()} bytes")
//                    } else {
//                        viewModel.onError("Ghi âm thất bại, thử lại!")
//                    }
//                    true
//                }
//                else -> false
//            }
//        }
//
//        binding.btnCancel.setOnClickListener {
//            viewModel.onCancelPlayback()
//        }
//    }
//
//
//    // ─── Xin quyền mic ───────────────────────────────────────────────────
//    private val requestPermissionLauncher =
//        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
//            if (!isGranted) {
//                Toast.makeText(this, "App cần quyền micro để hoạt động!", Toast.LENGTH_LONG).show()
//            }
//        }
//
//    private fun requestMicPermission() {
//        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
//            != PackageManager.PERMISSION_GRANTED
//        ) {
//            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
//        }
//    }
//
//}
