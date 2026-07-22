package org.jarsi.arkphone.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.jarsi.arkphone.data.CallLogRepository
import org.jarsi.arkphone.data.ContactsRepository
import org.jarsi.arkphone.data.SystemCallLogRepository
import org.jarsi.arkphone.data.SystemContactsRepository
import org.jarsi.arkphone.util.AndroidPermissionChecker
import org.jarsi.arkphone.util.Clock
import org.jarsi.arkphone.util.PermissionChecker
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindCallLogRepository(impl: SystemCallLogRepository): CallLogRepository

    @Binds
    @Singleton
    abstract fun bindContactsRepository(impl: SystemContactsRepository): ContactsRepository

    @Binds
    @Singleton
    abstract fun bindPermissionChecker(impl: AndroidPermissionChecker): PermissionChecker

    companion object {
        @Provides
        @IoDispatcher
        fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

        @Provides
        @Singleton
        fun provideClock(): Clock = Clock { System.currentTimeMillis() }
    }
}
