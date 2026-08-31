package com.chloemlla.aura.di

import android.content.Context
import androidx.room.Room
import com.chloemlla.aura.data.local.CollectionDao
import com.chloemlla.aura.data.local.DatabaseDowngradeGuard
import com.chloemlla.aura.data.local.DatabaseDowngradeReceiptStore
import com.chloemlla.aura.data.local.DatabaseMigrations
import com.chloemlla.aura.data.local.FREEVIBE_DATABASE_VERSION
import com.chloemlla.aura.data.local.DownloadDao
import com.chloemlla.aura.data.local.FavoriteDao
import com.chloemlla.aura.data.local.FreeVibeDatabase
import com.chloemlla.aura.data.local.LocalWallpaperDao
import com.chloemlla.aura.data.local.LocalWallpaperFolderDao
import com.chloemlla.aura.data.local.SearchHistoryDao
import com.chloemlla.aura.data.local.WallpaperCacheDao
import com.chloemlla.aura.data.local.WallpaperHistoryDao
import com.chloemlla.aura.data.model.providerRetryAfterHostSuffixes
import com.chloemlla.aura.data.remote.RateLimitInterceptor
import com.chloemlla.aura.data.remote.audius.AudiusApi
import com.chloemlla.aura.data.remote.bing.BingDailyApi
import com.chloemlla.aura.data.remote.ccmixter.CcMixterApi
import com.chloemlla.aura.data.remote.lemmy.LemmyApi
import com.chloemlla.aura.data.remote.nasa.NasaApodApi
import com.chloemlla.aura.data.remote.wikimedia.WikimediaPotdApi
import com.chloemlla.aura.data.remote.freesound.FreesoundV2Api
import com.chloemlla.aura.data.remote.weather.OpenMeteoApi
import com.chloemlla.aura.data.remote.pexels.PexelsApi
import com.chloemlla.aura.data.remote.pixabay.PixabayApi
import com.chloemlla.aura.data.remote.freesound.FreesoundApi
import com.chloemlla.aura.data.remote.soundcloud.SoundCloudApi
import com.chloemlla.aura.data.remote.wallhaven.WallhavenApi
import com.chloemlla.aura.service.ClashProxyManager
import com.squareup.moshi.Moshi
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.io.IOException
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // -- OkHttp --

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    fun provideOkHttpClient(clashProxyManager: ClashProxyManager): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // Whole-call bound: RateLimitInterceptor (added below) can sleep up to
        // maxRetries * retryCeilingMs = 2 * 30 s = 60 s across 429 retries, so a
        // 90 s callTimeout is the smallest round value that does not truncate that
        // documented retry budget while still capping a hung call at ~90 s.
        .callTimeout(90, TimeUnit.SECONDS)
        // Per-socket VPN binding: when the Clash VPN is active, every socket this
        // client creates is bound to the VPN network directly. This is reliable on
        // Android 10+, where process-level bindProcessToNetwork can silently no-op
        // and leave traffic bypassing Clash.
        .socketFactory(clashProxyManager.createVpnSocketFactory())
        // Dynamic proxy selector: queries Clash state at request time so the
        // OkHttpClient singleton is built once but adapts to VPN binding and
        // proxy availability changes throughout the process lifetime.
        .proxySelector(object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> {
                return if (clashProxyManager.shouldSkipManualProxy()) {
                    listOf(Proxy.NO_PROXY)
                } else {
                    val addr = clashProxyManager.proxyAddress()
                    if (addr != null) {
                        listOf(Proxy(Proxy.Type.HTTP, addr))
                    } else {
                        listOf(Proxy.NO_PROXY)
                    }
                }
            }
            override fun connectFailed(uri: URI?, sa: SocketAddress?, e: IOException?) {
                clashProxyManager.onProxyConnectFailed()
            }
        })
        .apply {
            if (com.chloemlla.aura.BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                })
            }
        }
        .addInterceptor { chain ->
            val original = chain.request()
            val request = if (original.header("User-Agent") == null) {
                original.newBuilder()
                    .header("User-Agent", "Aura/${com.chloemlla.aura.BuildConfig.VERSION_NAME} (Android; Open Source)")
                    .build()
            } else {
                original
            }
            chain.proceed(request)
        }
        // Bounded 429-aware retry for providers whose policy says Retry-After is safe to honor.
        // Other hosts pass through unchanged.
        .addInterceptor(RateLimitInterceptor(hostSuffixes = providerRetryAfterHostSuffixes()))
        .build()

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .build()

    // -- API Services --

    @Provides
    @Singleton
    fun provideWallhavenApi(client: OkHttpClient, moshi: Moshi): WallhavenApi =
        Retrofit.Builder()
            .baseUrl("https://wallhaven.cc/api/v1/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(WallhavenApi::class.java)

    @Provides
    @Singleton
    fun provideFreesoundV2Api(client: OkHttpClient, moshi: Moshi): FreesoundV2Api =
        Retrofit.Builder()
            .baseUrl(FreesoundV2Api.BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(FreesoundV2Api::class.java)

    @Provides
    @Singleton
    fun provideNasaApodApi(client: OkHttpClient, moshi: Moshi): NasaApodApi =
        Retrofit.Builder()
            .baseUrl(NasaApodApi.BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(NasaApodApi::class.java)

    @Provides
    @Singleton
    fun provideWikimediaPotdApi(client: OkHttpClient, moshi: Moshi): WikimediaPotdApi =
        Retrofit.Builder()
            .baseUrl(WikimediaPotdApi.BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(WikimediaPotdApi::class.java)

    @Provides
    @Singleton
    fun provideLemmyApi(client: OkHttpClient, moshi: Moshi): LemmyApi =
        Retrofit.Builder()
            .baseUrl(LemmyApi.BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(LemmyApi::class.java)

    @Provides
    @Singleton
    fun provideBingDailyApi(client: OkHttpClient, moshi: Moshi): BingDailyApi =
        Retrofit.Builder()
            .baseUrl(BingDailyApi.BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(BingDailyApi::class.java)

    @Provides
    @Singleton
    fun providePexelsApi(client: OkHttpClient, moshi: Moshi): PexelsApi =
        Retrofit.Builder()
            .baseUrl(PexelsApi.BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(PexelsApi::class.java)

    @Provides
    @Singleton
    fun providePixabayApi(client: OkHttpClient, moshi: Moshi): PixabayApi =
        Retrofit.Builder()
            .baseUrl(PixabayApi.BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(PixabayApi::class.java)

    @Provides
    @Singleton
    fun provideFreesoundApi(client: OkHttpClient, moshi: Moshi): FreesoundApi =
        Retrofit.Builder()
            .baseUrl("https://api.openverse.org/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(FreesoundApi::class.java)

    @Provides
    @Singleton
    fun provideOpenMeteoApi(client: OkHttpClient, moshi: Moshi): OpenMeteoApi =
        Retrofit.Builder()
            .baseUrl(OpenMeteoApi.BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(OpenMeteoApi::class.java)

    @Provides
    @Singleton
    fun provideSoundCloudApi(client: OkHttpClient, moshi: Moshi): SoundCloudApi =
        Retrofit.Builder()
            .baseUrl(SoundCloudApi.BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(SoundCloudApi::class.java)

    @Provides
    @Singleton
    fun provideAudiusApi(client: OkHttpClient, moshi: Moshi): AudiusApi =
        Retrofit.Builder()
            .baseUrl(AudiusApi.BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(AudiusApi::class.java)

    @Provides
    @Singleton
    fun provideCcMixterApi(client: OkHttpClient, moshi: Moshi): CcMixterApi =
        Retrofit.Builder()
            .baseUrl(CcMixterApi.BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(CcMixterApi::class.java)

    // -- Database --

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        downgradeReceipts: DatabaseDowngradeReceiptStore,
    ): FreeVibeDatabase {
        // Before Room touches the file. Opening a database whose schema is ahead
        // of this build is exactly what throws, so the check has to happen first,
        // and it reads the version out of the SQLite header rather than opening a
        // connection to ask.
        DatabaseDowngradeGuard
            .inspect(
                databaseFile = DatabaseDowngradeGuard.databaseFile(context),
                currentVersion = FREEVIBE_DATABASE_VERSION,
            )
            ?.let(downgradeReceipts::record)

        return Room.databaseBuilder(context, FreeVibeDatabase::class.java, DatabaseDowngradeGuard.DATABASE_NAME)
            .addMigrations(*DatabaseMigrations.ALL_MIGRATIONS)
            // Only on downgrade. An upgrade with a missing migration must still
            // fail loudly, because that is a bug in this build rather than a user
            // installing an older APK. The guard above has already copied the
            // database aside and recorded a receipt the UI turns into a warning,
            // so this drops tables that are provably recoverable.
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    super.onOpen(db)
                    db.execSQL("PRAGMA foreign_keys = ON")
                }
            })
            .build()
    }

    @Provides
    fun provideFavoriteDao(db: FreeVibeDatabase): FavoriteDao = db.favoriteDao()

    @Provides
    fun provideDownloadDao(db: FreeVibeDatabase): DownloadDao = db.downloadDao()

    @Provides
    fun provideSearchHistoryDao(db: FreeVibeDatabase): SearchHistoryDao = db.searchHistoryDao()

    @Provides
    fun provideWallpaperCacheDao(db: FreeVibeDatabase): WallpaperCacheDao = db.wallpaperCacheDao()

    @Provides
    fun provideWallpaperHistoryDao(db: FreeVibeDatabase): WallpaperHistoryDao = db.wallpaperHistoryDao()

    @Provides
    fun provideCollectionDao(db: FreeVibeDatabase): CollectionDao = db.collectionDao()

    @Provides
    fun provideLocalWallpaperFolderDao(db: FreeVibeDatabase): LocalWallpaperFolderDao = db.localWallpaperFolderDao()

    @Provides
    fun provideLocalWallpaperDao(db: FreeVibeDatabase): LocalWallpaperDao = db.localWallpaperDao()
}
