package com.mineradio.app.di

import com.linc.amplituda.Amplituda
import com.mineradio.app.core.preferences.PreferencesManager
import com.skydoves.sandwich.retrofit.adapters.ApiResponseCallAdapterFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * App 模块的依赖注入配置
 *
 * ★ 竖屏 Compose 界面（com.mineradio.app.ui.*）已整体移除，
 *   原先在此注册的 20 个竖屏 UI ViewModel 定义同步删除。
 *   数据层 / 领域层模块（storageModule、libraryDomainModule、playerDomainModule、
 *   settingsDomainModule、lyricsDomainModule 等）保持不变，横屏 H5 与壁纸功能不受影响。
 */
object AppModule {
    val appModule =
        module {
            // PreferencesManager
            single { PreferencesManager(androidContext()) }

            single<OkHttpClient>(
                createdAtStart = true,
            ) {
                OkHttpClient
                    .Builder()
                    .addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY))
                    .retryOnConnectionFailure(true)
                    .connectTimeout(3000L, TimeUnit.MILLISECONDS)
                    .readTimeout(3000L, TimeUnit.MILLISECONDS)
                    .callTimeout(3000L, TimeUnit.MILLISECONDS)
                    .writeTimeout(3000L, TimeUnit.MILLISECONDS)
                    .build()
            }
            single<Retrofit>(
                createdAtStart = true,
            ) {
                Retrofit
                    .Builder()
                    .client(get())
                    .baseUrl("http://api.spica27.site/api/v1/lyrics/")
                    .addConverterFactory(
                        MoshiConverterFactory
                            .create(Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build())
                            .withNullSerialization(),
                    ).addCallAdapterFactory(ApiResponseCallAdapterFactory.create())
                    .build()
            }

            // Amplituda 分析工具
            single { Amplituda(androidContext()) }
        }
}
