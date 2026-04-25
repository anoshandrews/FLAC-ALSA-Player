package dev.anosh.musicplayer.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import dev.anosh.musicplayer.audio.PlaylistEntry
import dev.anosh.musicplayer.databinding.ItemTrackBinding

class TrackListAdapter(
    private val onTrackSelected: (Int, PlaylistEntry) -> Unit,
) : RecyclerView.Adapter<TrackListAdapter.TrackViewHolder>() {
    private var items: List<PlaylistEntry> = emptyList()
    private var selectedPosition: Int = RecyclerView.NO_POSITION

    fun submitList(
        entries: List<PlaylistEntry>,
        selectedIndex: Int,
    ) {
        items = entries
        selectedPosition = selectedIndex
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val binding = ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TrackViewHolder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        holder.bind(items[position], position == selectedPosition)
    }

    inner class TrackViewHolder(
        private val binding: ItemTrackBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: PlaylistEntry, selected: Boolean) {
            binding.trackTitle.text = entry.displayName
            binding.trackRoot.alpha = if (selected) 1f else 0.82f
            binding.trackAccent.alpha = if (selected) 1f else 0f
            binding.trackArt.setImageResource(dev.anosh.musicplayer.R.drawable.ic_music_placeholder)
            binding.trackRoot.setOnClickListener {
                onTrackSelected(bindingAdapterPosition, entry)
            }
        }
    }
}
