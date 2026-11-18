package com.example.sprintly.auth

import com.example.sprintly.BuildConfig
import com.example.sprintly.offline.OfflineConnectivity
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class AuthScope

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AuthModule {
    @Binds
    abstract fun bindSessionStorage(storage: EncryptedSessionStorage): SessionStorage

    companion object {
        @Provides
        @Singleton
        fun provideAuthRemote(connectivity: OfflineConnectivity): AuthRemote =
            AuthRemote(BuildConfig.API_BASE_URL, connectivity::networkHandle)

        @Provides
        @Singleton
        @AuthScope
        fun provideAuthScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
