package dev.anosh.musicplayer.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.util.Log
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import dev.anosh.musicplayer.audio.AudioFileLoader
import dev.anosh.musicplayer.audio.AudioSelection
import dev.anosh.musicplayer.audio.DecodedAudioFile
import dev.anosh.musicplayer.audio.PlaybackController
import dev.anosh.musicplayer.audio.PlaylistEntry
import dev.anosh.musicplayer.audio.PlaylistScanner
import dev.anosh.musicplayer.databinding.ActivityPlayerBinding
import dev.anosh.musicplayer.usb.DirectUsbPlaybackController
import dev.anosh.musicplayer.usb.DirectUsbStartResult
import dev.anosh.musicplayer.usb.UsbAudioDeviceSummary
import dev.anosh.musicplayer.usb.UsbDeviceManager
import dev.anosh.musicplayer.usb.UsbStreamingEngine
import dev.anosh.musicplayer.usb.UsbStreamingPreparation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.anosh.musicplayer.storage.PlaylistStorage

class PlayerActivity : AppCompatActivity() {
    private val tag = "musicplayer-ui"
    private val usbPermissionAction = "dev.anosh.musicplayer.USB_PERMISSION"
    private lateinit var binding: ActivityPlayerBinding
    private val playbackController = PlaybackController()
    private lateinit var audioFileLoader: AudioFileLoader
    private lateinit var playlistScanner: PlaylistScanner
    private lateinit var usbDeviceManager: UsbDeviceManager
    private lateinit var directUsbPlaybackController: DirectUsbPlaybackController
    private val usbStreamingEngine = UsbStreamingEngine()
    private val playlistStorage by lazy { PlaylistStorage(applicationContext) }
    private var selectedAudioSelection: AudioSelection? = null
    private lateinit var audioManager: AudioManager
    private var decodedAudioCache: Pair<Uri, DecodedAudioFile>? = null
    private var playlist: List<PlaylistEntry> = emptyList()
    private var playlistIndex: Int = -1
    private var currentUsbSummaries: List<UsbAudioDeviceSummary> = emptyList()
    private var pendingUsbStart = false
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED,
                -> refreshUsbDevices()
                usbPermissionAction -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        pendingUsbStart = true
                        Log.i(tag, "USB permission granted")
                        selectedAudioSelection?.let { selection ->
                            lifecycleScope.launch {
                                startPlayback(selection)
                            }
                        }
                    } else {
                        Log.w(tag, "USB permission denied by system dialog")
                        binding.modeText.text = getString(dev.anosh.musicplayer.R.string.mode_usb_permission_denied)
                        pendingUsbStart = false
                    }
                    refreshUsbDevices()
                }
            }
        }
    }

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            loadAudio(uri)
        }
    }

    private val openTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            loadFolder(uri)
        }
    }

    private val openLibrary = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) {
            return@registerForActivityResult
        }
        val selectedIndex = result.data?.getIntExtra(LibraryActivity.EXTRA_SELECTED_INDEX, -1) ?: -1
        val selectedUri = result.data?.getStringExtra(LibraryActivity.EXTRA_SELECTED_URI)?.let(Uri::parse) ?: return@registerForActivityResult
        if (selectedIndex >= 0) {
            playlistIndex = selectedIndex
        }
        updatePlaylistUi()
        loadAudio(selectedUri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        setupVolumeSlider()
        volumeControlStream = AudioManager.STREAM_MUSIC
        audioFileLoader = AudioFileLoader(this)
        playlistScanner = PlaylistScanner(this)
        usbDeviceManager = UsbDeviceManager(this)
        directUsbPlaybackController = DirectUsbPlaybackController(this)
        directUsbPlaybackController.usbErrorListener = ::handleDirectUsbFailure
        playbackController.setPcmChunkListener { audioFile, chunk ->
            binding.audioVisualizerView.submitAudioChunk(audioFile, chunk)
        }

        binding.refreshUsbButton.setOnClickListener {
            refreshUsbDevices()
        }

        binding.pickFileButton.setOnClickListener {
            openDocument.launch(arrayOf("audio/wav", "audio/x-wav", "audio/flac", "audio/*"))
        }

        binding.pickFolderButton.setOnClickListener {
            openTree.launch(null)
        }

        binding.openLibraryButton.setOnClickListener {
            if (playlist.isEmpty()) {
                showMessage("Scan a folder first")
                return@setOnClickListener
            }
            LibrarySessionStore.entries = playlist
            LibrarySessionStore.selectedIndex = playlistIndex
            openLibrary.launch(Intent(this, LibraryActivity::class.java))
        }

        binding.playButton.setOnClickListener {
            val selection = selectedAudioSelection ?: return@setOnClickListener showMessage("Pick an audio file first")
            lifecycleScope.launch {
                runCatching {
                    startPlayback(selection)
                }.onFailure { error ->
                    showMessage(error.message ?: "Playback failed")
                }
            }
        }

        binding.stopButton.setOnClickListener {
            lifecycleScope.launch {
                binding.modeText.text = getString(dev.anosh.musicplayer.R.string.mode_stopped)
                binding.audioVisualizerView.setPlaybackActive(false)
                binding.playButton.setImageResource(dev.anosh.musicplayer.R.drawable.ic_play)
                binding.playButton.contentDescription = getString(dev.anosh.musicplayer.R.string.action_play_symbol)
                playbackController.stop()
                directUsbPlaybackController.stop()
                usbStreamingEngine.stop()
            }
        }

        binding.previousButton.setOnClickListener {
            movePlaylist(-1)
        }

        binding.nextButton.setOnClickListener {
            movePlaylist(1)
        }

        refreshUsbDevices()
        updatePlaylistUi()
        loadStoredPlaylist()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(usbPermissionAction)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbReceiver, filter)
        }
    }

    override fun onStop() {
        runCatching {
            unregisterReceiver(usbReceiver)
        }
        super.onStop()
    }

    override fun onDestroy() {
        playbackController.release()
        super.onDestroy()
    }

    private fun loadAudio(uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                val audioFile = withContext(Dispatchers.IO) {
                    audioFileLoader.inspect(uri)
                }
                if (decodedAudioCache?.first != uri) {
                    decodedAudioCache = null
                }
                selectedAudioSelection = audioFile
                Log.i(tag, "Selected audio file ${audioFile.displayName} ${audioFile.sampleRate ?: 0}Hz ${audioFile.channelCount ?: 0}ch ${audioFile.bitsPerSample ?: 0}-bit")
                binding.selectedTitleText.text = audioFile.displayName
                binding.playButton.isEnabled = true
                binding.modeText.text = getString(dev.anosh.musicplayer.R.string.mode_track_selected)
                binding.usbCandidateText.text = getString(
                    dev.anosh.musicplayer.R.string.no_usb_candidate_reason,
                    getString(dev.anosh.musicplayer.R.string.mode_decode_required),
                )
            }.onFailure { error ->
                Log.e(tag, "Audio file load failed", error)
                selectedAudioSelection = null
                decodedAudioCache = null
                binding.playButton.isEnabled = false
                binding.selectedTitleText.text = getString(dev.anosh.musicplayer.R.string.no_file_selected)
                binding.audioVisualizerView.setPlaybackActive(false)
                showMessage(error.message ?: "Unable to load audio file")
            }
        }
    }

    private fun loadFolder(treeUri: Uri) {
        lifecycleScope.launch {
            runCatching {
                val scanned = withContext(Dispatchers.IO) {
                    playlistScanner.scanTree(treeUri)
                }
                require(scanned.isNotEmpty()) { "No WAV or FLAC files found in the selected folder" }
                playlist = scanned
                playlistIndex = 0
                updatePlaylistUi()
                LibrarySessionStore.entries = scanned
                LibrarySessionStore.selectedIndex = playlistIndex
                lifecycleScope.launch {
                    playlistStorage.replaceAll(scanned)
                }
                openLibrary.launch(Intent(this@PlayerActivity, LibraryActivity::class.java))
            }.onFailure { error ->
                showMessage(error.message ?: "Unable to scan folder")
            }
        }
    }

    private fun movePlaylist(step: Int) {
        if (playlist.isEmpty()) {
            showMessage("Pick a folder first")
            return
        }
        val nextIndex = (playlistIndex + step).coerceIn(0, playlist.lastIndex)
        if (nextIndex == playlistIndex) {
            return
        }
        playlistIndex = nextIndex
        updatePlaylistUi()
        loadAudio(playlist[playlistIndex].uri)
    }

    private fun refreshUsbDevices() {
        currentUsbSummaries = usbDeviceManager.getConnectedAudioDevices()
        binding.usbInfoText.text = buildUsbDeviceText(currentUsbSummaries)
        decodedAudioCache?.second?.let(::renderUsbCandidate)
    }

    private fun buildUsbDeviceText(summaries: List<UsbAudioDeviceSummary>): String {
        if (summaries.isEmpty()) {
            return getString(dev.anosh.musicplayer.R.string.no_usb_audio_devices)
        }

        return summaries.joinToString(separator = "\n\n") { it.toDisplayString() }
    }

    private fun renderUsbCandidate(audioFile: DecodedAudioFile) {
        binding.usbCandidateText.text = when (val preparation = usbStreamingEngine.prepare(audioFile, currentUsbSummaries)) {
            is UsbStreamingPreparation.Ready -> {
                usbStreamingEngine.activate(preparation.candidate)
                Log.i(tag, "USB candidate selected ${preparation.candidate.toDisplayString()} reconfigure=${preparation.needsReconfigure}")
                getString(
                    dev.anosh.musicplayer.R.string.usb_candidate_ready,
                    preparation.candidate.toDisplayString(),
                )
            }

            is UsbStreamingPreparation.Unavailable -> {
                Log.w(tag, "No USB candidate: ${preparation.reason}")
                getString(dev.anosh.musicplayer.R.string.no_usb_candidate_reason, preparation.reason)
            }
        }
    }

    private suspend fun startPlayback(selection: AudioSelection) {
        binding.modeText.text = getString(dev.anosh.musicplayer.R.string.mode_loading_track)
        binding.audioVisualizerView.setPlaybackActive(true)
        val audioFile = resolveDecodedAudio(selection)
        lifecycleScope.launch(Dispatchers.IO) {
            playlistStorage.markPlayed(selection.uri)
        }
        renderUsbCandidate(audioFile)
        val preparation = usbStreamingEngine.prepare(audioFile, currentUsbSummaries)
        if (preparation is UsbStreamingPreparation.Ready) {
            val candidate = preparation.candidate
            if (!usbDeviceManager.hasPermission(candidate.deviceName)) {
                Log.i(tag, "Requesting USB permission for ${candidate.deviceLabel}")
                usbDeviceManager.requestPermission(candidate.deviceName, usbPermissionAction)
                binding.modeText.text = getString(dev.anosh.musicplayer.R.string.mode_requesting_usb)
                return
            }

            when (val directResult = directUsbPlaybackController.play(audioFile, candidate)) {
                is DirectUsbStartResult.Started -> {
                    pendingUsbStart = false
                    Log.i(tag, "Direct USB mode active for ${directResult.candidate.deviceLabel}")
                    binding.modeText.text = getString(
                        dev.anosh.musicplayer.R.string.mode_direct_usb,
                        directResult.candidate.deviceLabel,
                    )
                    binding.playButton.setImageResource(dev.anosh.musicplayer.R.drawable.ic_pause)
                    binding.playButton.contentDescription = getString(dev.anosh.musicplayer.R.string.action_pause_symbol)
                    return
                }

                is DirectUsbStartResult.PermissionRequired -> {
                    Log.w(tag, "Direct USB permission required: ${directResult.reason}")
                    binding.modeText.text = directResult.reason
                    return
                }

                is DirectUsbStartResult.Unsupported -> {
                    Log.w(tag, "Direct USB unsupported: ${directResult.reason}")
                    binding.modeText.text = getString(
                        dev.anosh.musicplayer.R.string.mode_fallback,
                        directResult.reason,
                    )
                }

                is DirectUsbStartResult.Failed -> {
                    Log.e(tag, "Direct USB failed: ${directResult.reason}")
                    binding.modeText.text = getString(
                        dev.anosh.musicplayer.R.string.mode_fallback,
                        directResult.reason,
                    )
                }
            }
        } else {
            Log.w(tag, "No direct USB path available; falling back to AudioTrack")
            binding.modeText.text = getString(
                dev.anosh.musicplayer.R.string.mode_fallback,
                getString(dev.anosh.musicplayer.R.string.mode_no_usb_path),
            )
        }

        Log.i(tag, "Starting AudioTrack fallback path")
        playbackController.play(audioFile)
        binding.playButton.setImageResource(dev.anosh.musicplayer.R.drawable.ic_pause)
        binding.playButton.contentDescription = getString(dev.anosh.musicplayer.R.string.action_pause_symbol)
    }

    private suspend fun resolveDecodedAudio(selection: AudioSelection): DecodedAudioFile {
        decodedAudioCache?.takeIf { it.first == selection.uri }?.let { return it.second }
        val decoded = withContext(Dispatchers.IO) {
            audioFileLoader.load(selection.uri)
        }
        decodedAudioCache = selection.uri to decoded
        Log.i(tag, "Decoded audio file ${decoded.displayName} ${decoded.sampleRate}Hz ${decoded.channelCount}ch ${decoded.bitsPerSample}-bit bytes=${decoded.pcmData.size}")
        return decoded
    }

    private fun updatePlaylistUi() {
        val hasPlaylist = playlist.isNotEmpty()
        binding.openLibraryButton.isEnabled = hasPlaylist
        binding.previousButton.isVisible = hasPlaylist
        binding.nextButton.isVisible = hasPlaylist
    }

    private fun setupVolumeSlider() {
        binding.volumeSeekBar.max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        binding.volumeSeekBar.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        binding.volumeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun handleDirectUsbFailure(reason: String) {
        lifecycleScope.launch {
            directUsbPlaybackController.stop()
            playbackController.stop()
            binding.modeText.text = getString(
                dev.anosh.musicplayer.R.string.mode_fallback,
                reason,
            )
            decodedAudioCache?.second?.let { fallback ->
                playbackController.play(fallback)
                binding.playButton.setImageResource(dev.anosh.musicplayer.R.drawable.ic_pause)
                binding.playButton.contentDescription = getString(dev.anosh.musicplayer.R.string.action_pause_symbol)
            }
        }
    }

    private fun loadStoredPlaylist() {
        lifecycleScope.launch {
            val stored = playlistStorage.loadAll()
            if (stored.isNotEmpty()) {
                playlist = stored
                playlistIndex = 0
                LibrarySessionStore.entries = playlist
                LibrarySessionStore.selectedIndex = playlistIndex
                updatePlaylistUi()
            }
        }
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

}
