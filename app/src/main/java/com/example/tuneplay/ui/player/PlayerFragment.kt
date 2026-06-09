package com.example.tuneplay.ui.player

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.tuneplay.R
import com.example.tuneplay.data.local.AppDatabase
import com.example.tuneplay.data.local.entity.Song
import com.example.tuneplay.data.repository.MusicRepository
import com.example.tuneplay.databinding.FragmentPlayerBinding
import com.example.tuneplay.player.MusicController
import com.example.tuneplay.ui.home.formatDuration
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class PlayerFragment : Fragment() {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: MusicRepository
    private var currentSongId: Long = 0L
    private var isLiked = false
    private var isUserSeeking = false
    private var currentQueue: List<Song> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val db = AppDatabase.getInstance(requireContext())
        repository = MusicRepository(db.songDao(), db.historyDao(), db.playlistDao(), db.searchHistoryDao())

        // When a playlist is modified via the Add sheet, refresh like state
        parentFragmentManager.setFragmentResultListener("playlist_changed", viewLifecycleOwner) { _, _ ->
            refreshLikeStatus()
        }

        setupDeckView()
        setupSeekBar()
        setupControls()
        setupActionButtons()
        setupNavigation()
        collectState()
    }

    private fun setupDeckView() {
        binding.deckView.callback = object : StackedDeckView.Callback {
            override val currentIndex: Int
                get() {
                    val song = MusicController.getCurrentSong() ?: return 0
                    return currentQueue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                }

            override val itemCount: Int
                get() = currentQueue.size

            override fun loadCover(index: Int, imageView: ImageView) {
                if (index in currentQueue.indices) {
                    val coverPath = currentQueue[index].coverArtPath
                    // Use view's context — safe even if fragment is detached
                    imageView.post {
                        Glide.with(imageView.context)
                            .load(coverPath)
                            .placeholder(R.drawable.ic_music_note)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .centerCrop()
                            .into(imageView)
                    }
                }
            }

            override fun onSwitchTo(index: Int) {
                if (!isAdded || index !in currentQueue.indices) return
                MusicController.play(requireContext(), currentQueue, index)
            }

            override fun onLike() {
                if (!isAdded) return
                viewLifecycleOwner.lifecycleScope.launch {
                    val likedPlaylist = repository.getPlaylistByName(
                        getString(R.string.playlist_liked)
                    ) ?: return@launch
                    isLiked = repository.toggleSongInPlaylist(likedPlaylist.id, currentSongId)
                    updateLikeButton()
                }
            }

            override fun onSkip() {
                if (!isAdded) return
                MusicController.skipNext(requireContext())
            }

            override fun onFullScreen() {
                if (!isAdded) return
                val song = MusicController.getCurrentSong() ?: return
                val dialog = android.app.Dialog(
                    requireContext(),
                    android.R.style.Theme_Black_NoTitleBar_Fullscreen
                )
                val imageView = ImageView(requireContext()).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setOnClickListener { dialog.dismiss() }
                }
                Glide.with(imageView.context)
                    .load(song.coverArtPath)
                    .placeholder(R.drawable.ic_music_note)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(imageView)
                dialog.setContentView(imageView)
                dialog.window?.addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                )
                dialog.show()
            }
        }
    }

    private fun setupSeekBar() {
        binding.seekPlayer.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvPlayerCurrentTime.text = formatDuration(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = false
                seekBar?.progress?.let { progress ->
                    MusicController.seekTo(requireContext(), progress.toLong())
                }
            }
        })
    }

    private fun setupControls() {
        binding.btnPlayerPrev.setOnClickListener {
            MusicController.skipPrevious(requireContext())
        }
        binding.btnPlayerPlayPause.setOnClickListener {
            MusicController.togglePlayPause(requireContext())
        }
        binding.btnPlayerNext.setOnClickListener {
            MusicController.skipNext(requireContext())
        }
        binding.btnPlayerShuffle.setOnClickListener {
            MusicController.cyclePlayMode()
        }
        binding.btnPlayerRepeat.setOnClickListener {
            showPlaylistBottomSheet()
        }
    }

    private fun showPlaylistBottomSheet() {
        val playlist = MusicController.currentPlaylist.value
        if (playlist.isEmpty()) {
            Toast.makeText(requireContext(), "播放列表为空", Toast.LENGTH_SHORT).show()
            return
        }
        PlaylistSheetFragment(playlist).show(parentFragmentManager, "PlaylistSheet")
    }

    private fun setupActionButtons() {
        binding.btnPlayerLike.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val likedPlaylist = repository.getPlaylistByName(
                    getString(R.string.playlist_liked)
                ) ?: return@launch
                isLiked = repository.toggleSongInPlaylist(likedPlaylist.id, currentSongId)
                updateLikeButton()
                if (isLiked) {
                    Toast.makeText(requireContext(), "已添加到收藏歌单", Toast.LENGTH_SHORT).show()
                }
            }
        }
        binding.btnPlayerAdd.setOnClickListener {
            if (currentSongId != 0L) {
                PlaylistSelectSheetFragment.newInstance(currentSongId)
                    .show(parentFragmentManager, "PlaylistSelectSheet")
            }
        }
    }

    private fun setupNavigation() {
        binding.btnPlayerBack.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.viewLyricsTrigger.setOnClickListener {
            findNavController().navigate(R.id.action_playerFragment_to_lyricsFragment)
        }
    }

    private fun updateLikeButton() {
        binding.btnPlayerLike.setImageResource(
            if (isLiked) R.drawable.ic_heart_filled else R.drawable.ic_heart
        )
    }

    private fun refreshLikeStatus() {
        if (!isAdded) return
        lifecycleScope.launch {
            val likedPlaylist = repository.getPlaylistByName(
                getString(R.string.playlist_liked)
            ) ?: return@launch
            isLiked = repository.isSongInPlaylist(likedPlaylist.id, currentSongId)
            updateLikeButton()
        }
    }

    private fun collectState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    MusicController.currentSong.collect { song ->
                        if (song != null) {
                            binding.tvPlayerTitle.text = song.title
                            binding.tvPlayerArtist.text = song.artist

                            val songChanged = song.id != currentSongId
                            currentSongId = song.id
                            lifecycleScope.launch {
                                val likedPlaylist = repository.getPlaylistByName(
                                    getString(R.string.playlist_liked)
                                )
                                if (likedPlaylist != null) {
                                    isLiked = repository.isSongInPlaylist(
                                        likedPlaylist.id, song.id
                                    )
                                    updateLikeButton()
                                }
                            }
                            if (songChanged) {
                                binding.deckView.refreshCards()
                            }
                        }
                    }
                }
                launch {
                    MusicController.isPlaying.collect { playing ->
                        binding.btnPlayerPlayPause.setImageResource(
                            if (playing) android.R.drawable.ic_media_pause
                            else android.R.drawable.ic_media_play
                        )
                    }
                }
                launch {
                    combine(
                        MusicController.position,
                        MusicController.duration
                    ) { pos, dur -> Pair(pos, dur) }.collect { (pos, dur) ->
                        if (!isUserSeeking) {
                            binding.tvPlayerCurrentTime.text = formatDuration(pos)
                            binding.tvPlayerDuration.text = formatDuration(dur)
                            if (dur > 0) {
                                binding.seekPlayer.max = dur.toInt()
                                binding.seekPlayer.progress = pos.toInt()
                            }
                        }
                    }
                }
                launch {
                    MusicController.currentPlaylist.collect { songs ->
                        currentQueue = songs
                        binding.deckView.refreshCards()
                    }
                }
                launch {
                    MusicController.playlistName.collect { name ->
                        binding.tvPlaylistName.text = name
                    }
                }
                launch {
                    MusicController.playMode.collect { mode ->
                        // Left button: play mode indicator
                        binding.btnPlayerShuffle.setImageResource(
                            when (mode) {
                                0 -> R.drawable.ic_shuffle       // sequential: gray shuffle
                                1 -> R.drawable.ic_repeat_one    // repeat one
                                else -> R.drawable.ic_shuffle     // shuffle
                            }
                        )
                        binding.btnPlayerShuffle.imageTintList = ColorStateList.valueOf(
                            if (mode != 0) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt()
                        )
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
