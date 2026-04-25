package dev.anosh.musicplayer.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import dev.anosh.musicplayer.databinding.ActivityLibraryBinding

class LibraryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLibraryBinding
    private lateinit var trackListAdapter: TrackListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        trackListAdapter = TrackListAdapter { index, entry ->
            LibrarySessionStore.selectedIndex = index
            setResult(
                Activity.RESULT_OK,
                Intent()
                    .putExtra(EXTRA_SELECTED_INDEX, index)
                    .putExtra(EXTRA_SELECTED_URI, entry.uri.toString()),
            )
            finish()
        }

        binding.backButton.setOnClickListener {
            finish()
        }

        binding.trackList.layoutManager = LinearLayoutManager(this)
        binding.trackList.adapter = trackListAdapter
        trackListAdapter.submitList(LibrarySessionStore.entries, LibrarySessionStore.selectedIndex)
    }

    companion object {
        const val EXTRA_SELECTED_INDEX = "selected_index"
        const val EXTRA_SELECTED_URI = "selected_uri"
    }
}
