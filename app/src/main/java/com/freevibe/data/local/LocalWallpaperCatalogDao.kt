package com.freevibe.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.freevibe.data.model.LocalWallpaperEntity
import com.freevibe.data.model.LocalWallpaperFolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalWallpaperFolderDao {
    @Query("SELECT * FROM local_wallpaper_folders ORDER BY addedAt ASC")
    fun observeAll(): Flow<List<LocalWallpaperFolderEntity>>

    @Query("SELECT * FROM local_wallpaper_folders ORDER BY addedAt ASC")
    suspend fun getAll(): List<LocalWallpaperFolderEntity>

    @Query("SELECT * FROM local_wallpaper_folders WHERE folderUri = :folderUri LIMIT 1")
    suspend fun get(folderUri: String): LocalWallpaperFolderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(folder: LocalWallpaperFolderEntity)

    @Query(
        "UPDATE local_wallpaper_folders SET target = :target WHERE folderUri = :folderUri",
    )
    suspend fun updateTarget(folderUri: String, target: String)

    @Query(
        "UPDATE local_wallpaper_folders SET lastScannedAt = :lastScannedAt, " +
            "scanStatus = :scanStatus, lastError = :lastError, itemCount = :itemCount " +
            "WHERE folderUri = :folderUri",
    )
    suspend fun updateScanState(
        folderUri: String,
        lastScannedAt: Long,
        scanStatus: String,
        lastError: String,
        itemCount: Int,
    )

    @Delete
    suspend fun delete(folder: LocalWallpaperFolderEntity)
}

@Dao
interface LocalWallpaperDao {
    @Query("SELECT * FROM local_wallpapers ORDER BY displayName COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<LocalWallpaperEntity>>

    @Query("SELECT * FROM local_wallpapers ORDER BY displayName COLLATE NOCASE ASC")
    suspend fun getAll(): List<LocalWallpaperEntity>

    @Query("SELECT * FROM local_wallpapers WHERE folderUri = :folderUri")
    suspend fun getByFolder(folderUri: String): List<LocalWallpaperEntity>

    @Query("SELECT * FROM local_wallpapers WHERE documentUri = :documentUri LIMIT 1")
    suspend fun get(documentUri: String): LocalWallpaperEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<LocalWallpaperEntity>)

    @Query(
        "DELETE FROM local_wallpapers WHERE folderUri = :folderUri " +
            "AND lastSeenScanToken != :scanToken",
    )
    suspend fun deleteNotSeenInScan(folderUri: String, scanToken: String)

    @Query("DELETE FROM local_wallpapers WHERE folderUri = :folderUri")
    suspend fun deleteByFolder(folderUri: String)

    @Query("UPDATE local_wallpapers SET tags = :tags WHERE documentUri = :documentUri")
    suspend fun updateTags(documentUri: String, tags: String)
}
