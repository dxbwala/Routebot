package com.routedns.routebot.data.di

import android.content.Context
import androidx.room.Room
import com.routedns.routebot.data.local.RouteBotDatabase
import com.routedns.routebot.data.local.dao.QueuedEventDao
import com.routedns.routebot.data.repository.AgentApiRepositoryImpl
import com.routedns.routebot.data.repository.AuthRepositoryImpl
import com.routedns.routebot.data.repository.ConfigRepositoryImpl
import com.routedns.routebot.data.repository.DeviceRepositoryImpl
import com.routedns.routebot.data.repository.JsonProvider
import com.routedns.routebot.data.repository.OfflineQueueRepositoryImpl
import com.routedns.routebot.data.security.SecureStorageRepositoryImpl
import com.routedns.routebot.domain.repository.AgentApiRepository
import com.routedns.routebot.domain.repository.AuthRepository
import com.routedns.routebot.domain.repository.ConfigRepository
import com.routedns.routebot.domain.repository.DeviceRepository
import com.routedns.routebot.domain.repository.OfflineQueueRepository
import com.routedns.routebot.domain.repository.SecureStorageRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindModule {
    @Binds @Singleton abstract fun bindSecureStorage(impl: SecureStorageRepositoryImpl): SecureStorageRepository
    @Binds @Singleton abstract fun bindAuth(impl: AuthRepositoryImpl): AuthRepository
    @Binds @Singleton abstract fun bindAgentApi(impl: AgentApiRepositoryImpl): AgentApiRepository
    @Binds @Singleton abstract fun bindOfflineQueue(impl: OfflineQueueRepositoryImpl): OfflineQueueRepository
    @Binds @Singleton abstract fun bindConfig(impl: ConfigRepositoryImpl): ConfigRepository
    @Binds @Singleton abstract fun bindDevice(impl: DeviceRepositoryImpl): DeviceRepository
}

@Module
@InstallIn(SingletonComponent::class)
object DataProvideModule {
    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): RouteBotDatabase =
        Room.databaseBuilder(context, RouteBotDatabase::class.java, "routebot.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideQueuedEventDao(db: RouteBotDatabase): QueuedEventDao = db.queuedEventDao()

    @Provides @Singleton
    fun provideJson(provider: JsonProvider): Json = provider.json

    @Provides @Singleton
    fun provideAgentApiImpl(impl: AgentApiRepositoryImpl): AgentApiRepositoryImpl = impl
}
