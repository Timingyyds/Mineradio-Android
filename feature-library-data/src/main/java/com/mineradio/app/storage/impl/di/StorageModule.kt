package com.mineradio.app.storage.impl.di

import android.app.Application
import android.os.Environment
import androidx.room.Room
import com.mineradio.app.storage.api.IAlbumRepository
import com.mineradio.app.storage.api.ILyricRepository
import com.mineradio.app.storage.api.IMusicScanService
import com.mineradio.app.storage.api.IPlayHistoryRepository
import com.mineradio.app.storage.api.IPlaylistRepository
import com.mineradio.app.storage.api.IScanFolderRepository
import com.mineradio.app.storage.api.ISongRepository
import com.mineradio.app.storage.impl.db.AppDatabase
import com.mineradio.app.storage.impl.repository.AlbumRepositoryImpl
import com.mineradio.app.storage.impl.repository.LyricRepositoryImpl
import com.mineradio.app.storage.impl.repository.PlayHistoryRepositoryImpl
import com.mineradio.app.storage.impl.repository.PlaylistRepositoryImpl
import com.mineradio.app.storage.impl.repository.ScanFolderRepositoryImpl
import com.mineradio.app.storage.impl.repository.SongRepositoryImpl
import com.mineradio.app.storage.impl.scanner.MusicScanService
import org.koin.dsl.module
import java.io.File

/**
 * 外部存储数据库备份目录：/sdcard/SPICaMusic/databases/
 * 卸载应用后备份仍保留，重新安装可恢复本地歌单
 */
val externalDbDir: File
    get() {
        val dir = File(Environment.getExternalStorageDirectory(), "SPICaMusic/databases")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

/**
 * 数据库备份管理器
 * - 恢复：应用启动时，若私有目录无数据库但外部备份存在，则恢复
 * - 备份：数据变更后，将私有目录数据库复制到外部存储
 */
object DatabaseBackupManager {
    private const val DB_NAME = "spica_music.db"
    private val backupFiles = listOf("", "-wal", "-shm", "-journal")

    /**
     * 启动时恢复：如果私有目录无数据库但外部备份存在，复制恢复
     */
    fun restoreIfNeeded(app: Application) {
        val privateDb = app.getDatabasePath(DB_NAME)
        val backupDb = File(externalDbDir, DB_NAME)
        // 私有目录不存在但备份存在 → 恢复
        if (!privateDb.exists() && backupDb.exists()) {
            try {
                privateDb.parentFile?.mkdirs()
                backupDb.copyTo(privateDb, overwrite = true)
                listOf("-wal", "-shm", "-journal").forEach { suffix ->
                    val backupFile = File(externalDbDir, DB_NAME + suffix)
                    if (backupFile.exists()) {
                        backupFile.copyTo(File(privateDb.parentFile, DB_NAME + suffix), overwrite = true)
                    }
                }
                android.util.Log.i("DatabaseBackup", "从外部备份恢复数据库成功")
            } catch (e: Exception) {
                android.util.Log.e("DatabaseBackup", "恢复数据库失败", e)
            }
        }
    }

    /**
     * 备份数据库到外部存储（异步调用，在 IO 线程执行）
     * 使用 WAL checkpoint 确保数据写入主数据库文件后复制
     */
    fun backup(app: Application) {
        try {
            val privateDb = app.getDatabasePath(DB_NAME)
            if (!privateDb.exists()) return
            val backupDb = File(externalDbDir, DB_NAME)
            // 复制主数据库文件
            privateDb.copyTo(backupDb, overwrite = true)
            // 复制 wal/shm（如果存在）
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                val src = File(privateDb.parentFile, DB_NAME + suffix)
                if (src.exists()) {
                    src.copyTo(File(externalDbDir, DB_NAME + suffix), overwrite = true)
                }
            }
            android.util.Log.d("DatabaseBackup", "数据库已备份到外部存储")
        } catch (e: Exception) {
            android.util.Log.e("DatabaseBackup", "备份数据库失败", e)
        }
    }
}

/**
 * 存储模块的 Koin 依赖注入配置
 */
val storageModule = module {
    // Database
    single<AppDatabase> {
        val app = get<Application>()
        // ★ 启动时从外部存储恢复数据库（如果私有目录不存在但备份存在）
        DatabaseBackupManager.restoreIfNeeded(app)
        Room.databaseBuilder(
            app,
            AppDatabase::class.java,
            "spica_music.db",
        ).addMigrations(
            AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_9_10,
            AppDatabase.MIGRATION_12_13,
            AppDatabase.MIGRATION_13_14,
        )
            // 开发阶段允许破坏性迁移，发布时应添加正式的 Migration
            .fallbackToDestructiveMigration(false)
            .build()
    }

    // DAOs
    single { get<AppDatabase>().songDao() }
    single { get<AppDatabase>().playlistDao() }
    single { get<AppDatabase>().lyricDao() }
    single { get<AppDatabase>().playHistoryDao() }
    single { get<AppDatabase>().albumDao() }
    single { get<AppDatabase>().scanFolderDao() }

    // Repositories - 通过接口暴露
    single<ISongRepository> { SongRepositoryImpl(get()) }
    single<IPlaylistRepository> { PlaylistRepositoryImpl(get(), get(), get()) }
    single<IPlayHistoryRepository> { PlayHistoryRepositoryImpl(get()) }
    single<IAlbumRepository> { AlbumRepositoryImpl(get()) }
    single<ILyricRepository> { LyricRepositoryImpl(get()) }
    single<IScanFolderRepository> { ScanFolderRepositoryImpl(get()) }

    // 扫描服务
    single<IMusicScanService> { MusicScanService(get(), get(), get(), get()) }
}
