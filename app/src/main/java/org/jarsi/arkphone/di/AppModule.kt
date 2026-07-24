package org.jarsi.arkphone.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.jarsi.arkphone.data.AndroidBlockedNumbersRepository
import org.jarsi.arkphone.data.BlockedNumbersRepository
import org.jarsi.arkphone.data.CallLogRepository
import org.jarsi.arkphone.data.ContactsRepository
import org.jarsi.arkphone.data.DataStoreSettingsRepository
import org.jarsi.arkphone.data.SettingsRepository
import org.jarsi.arkphone.data.SimRepository
import org.jarsi.arkphone.data.SystemCallLogRepository
import org.jarsi.arkphone.data.SystemContactsRepository
import org.jarsi.arkphone.data.SystemSimRepository
import org.jarsi.arkphone.telecom.AndroidAnnounceGate
import org.jarsi.arkphone.telecom.AnnounceGate
import org.jarsi.arkphone.telecom.SpeechEngine
import org.jarsi.arkphone.telecom.TtsSpeechEngine
import org.jarsi.arkphone.util.AndroidNotificationAccessChecker
import org.jarsi.arkphone.util.AndroidPermissionChecker
import org.jarsi.arkphone.util.Clock
import org.jarsi.arkphone.util.NotificationAccessChecker
import org.jarsi.arkphone.util.PermissionChecker
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

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

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: DataStoreSettingsRepository): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindSpeechEngine(impl: TtsSpeechEngine): SpeechEngine

    @Binds
    @Singleton
    abstract fun bindAnnounceGate(impl: AndroidAnnounceGate): AnnounceGate

    @Binds
    @Singleton
    abstract fun bindSimRepository(impl: SystemSimRepository): SimRepository

    @Binds
    @Singleton
    abstract fun bindBlockedNumbersRepository(
        impl: AndroidBlockedNumbersRepository,
    ): BlockedNumbersRepository

    @Binds
    @Singleton
    abstract fun bindNotificationAccessChecker(
        impl: AndroidNotificationAccessChecker,
    ): NotificationAccessChecker

    companion object {
        @Provides
        @IoDispatcher
        fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

        @Provides
        @Singleton
        fun provideClock(): Clock = Clock { System.currentTimeMillis() }

        @Provides
        @Singleton
        @ApplicationScope
        fun provideApplicationScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        @Provides
        @Singleton
        fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
            PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }
    }
}
