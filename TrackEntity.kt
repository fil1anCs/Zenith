package com.zenith.player.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val uri: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0,
    val dateAdded: Long = System.currentTimeMillis(),
    val isLiked: Boolean = false,
    val lastPlayed: Long = 0,
    val videoCoverUri: String? = null,
    val artworkUrl: String? = null
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "playlist_tracks", primaryKeys = ["playlistId", "trackUri"])
data class PlaylistTrackCrossRef(val playlistId: String, val trackUri: String, val position: Int)

@Entity(tableName = "sync_queue")
data class PendingSyncEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val payload: String,
    val createdAt: Long = System.currentTimeMillis()
)
