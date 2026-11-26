package com.zenith.player.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ZenithDao {
    @Query("SELECT * FROM tracks ORDER BY dateAdded DESC") fun observeTracks(): Flow<List<TrackEntity>>
    @Query("SELECT * FROM tracks WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' ORDER BY title") fun searchTracks(query: String): Flow<List<TrackEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertTrack(track: TrackEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertTracks(tracks: List<TrackEntity>)
    @Query("UPDATE tracks SET isLiked = :liked WHERE uri = :uri") suspend fun setLiked(uri: String, liked: Boolean)
    @Query("UPDATE tracks SET videoCoverUri = :videoUri WHERE uri = :uri") suspend fun setVideoCover(uri: String, videoUri: String?)
    @Query("UPDATE tracks SET artworkUrl = :artworkUrl WHERE uri = :uri") suspend fun setArtwork(uri: String, artworkUrl: String?)
    @Query("SELECT * FROM tracks WHERE isLiked = 1 ORDER BY dateAdded DESC") fun observeLiked(): Flow<List<TrackEntity>>
    @Query("DELETE FROM tracks WHERE uri = :uri") suspend fun deleteTrack(uri: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertPlaylist(playlist: PlaylistEntity)
    @Query("SELECT * FROM playlists ORDER BY updatedAt DESC") fun observePlaylists(): Flow<List<PlaylistEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun addPlaylistTrack(ref: PlaylistTrackCrossRef)
    @Query("SELECT t.* FROM tracks t INNER JOIN playlist_tracks p ON t.uri = p.trackUri WHERE p.playlistId = :playlistId ORDER BY p.position") fun observePlaylistTracks(playlistId: String): Flow<List<TrackEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun enqueueSync(item: PendingSyncEntity)
    @Query("SELECT * FROM sync_queue ORDER BY createdAt ASC") suspend fun pendingSync(): List<PendingSyncEntity>
    @Query("DELETE FROM sync_queue WHERE id IN (:ids)") suspend fun removeSynced(ids: List<Long>)
}
