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
import androidx.room.Room
import org.jarsi.arkphone.data.AndroidBlockedNumbersRepository
import org.jarsi.arkphone.data.ArkPhoneDatabase
import org.jarsi.arkphone.data.BlockedNumbersRepository
import org.jarsi.arkphone.data.DataStoreBlockedNumbersRepository
import org.jarsi.arkphone.data.SystemBlockedNumberList
import org.jarsi.arkphone.data.CallLogRepository
import org.jarsi.arkphone.data.CombinedCallLogRepository
import org.jarsi.arkphone.data.ContactsRepository
import org.jarsi.arkphone.data.DataStoreSettingsRepository
import org.jarsi.arkphone.data.RoomWhatsAppCallLogRepository
import org.jarsi.arkphone.data.SettingsRepository
import org.jarsi.arkphone.data.DataStoreSpeedDialRepository
import org.jarsi.arkphone.data.SimAccountRepository
import org.jarsi.arkphone.data.SimRepository
import org.jarsi.arkphone.data.TelecomSimAccountRepository
import org.jarsi.arkphone.data.SpeedDialRepository
import org.jarsi.arkphone.data.MessagesRepository
import org.jarsi.arkphone.data.SystemCallLogRepository
import org.jarsi.arkphone.data.SystemContactsRepository
import org.jarsi.arkphone.data.SystemMessagesRepository
import org.jarsi.arkphone.data.SystemSimRepository
import org.jarsi.arkphone.data.WhatsAppCallDao
import org.jarsi.arkphone.data.WhatsAppCallLogRepository
import org.jarsi.arkphone.messaging.AndroidMessageNotifier
import org.jarsi.arkphone.messaging.AndroidMessageSharer
import org.jarsi.arkphone.messaging.AndroidMmsSender
import org.jarsi.arkphone.messaging.AndroidSmsRole
import org.jarsi.arkphone.messaging.AndroidSmsSender
import org.jarsi.arkphone.messaging.MessageNotifier
import org.jarsi.arkphone.messaging.MessageSharer
import org.jarsi.arkphone.messaging.MmsSender
import org.jarsi.arkphone.messaging.MmsTransport
import org.jarsi.arkphone.messaging.PlatformMmsTransport
import org.jarsi.arkphone.messaging.SmsRole
import org.jarsi.arkphone.messaging.SmsSender
import org.jarsi.arkphone.telecom.AndroidAnnounceGate
import org.jarsi.arkphone.telecom.AndroidCallScreeningRole
import org.jarsi.arkphone.telecom.AndroidProximityLock
import org.jarsi.arkphone.telecom.AndroidRingSilencer
import org.jarsi.arkphone.telecom.ProximityLock
import org.jarsi.arkphone.telecom.AndroidSpeechAvailability
import org.jarsi.arkphone.telecom.AndroidTelecomCallPlacer
import org.jarsi.arkphone.telecom.SpeechAvailability
import org.jarsi.arkphone.telecom.TelecomCallPlacer
import org.jarsi.arkphone.telecom.AnnounceGate
import org.jarsi.arkphone.telecom.CallScreeningRole
import org.jarsi.arkphone.telecom.RejectMessageSender
import org.jarsi.arkphone.telecom.RingSilencer
import org.jarsi.arkphone.telecom.SmsRejectMessageSender
import org.jarsi.arkphone.telecom.SpeechEngine
import org.jarsi.arkphone.telecom.TtsSpeechEngine
import org.jarsi.arkphone.telecom.WhatsAppAvailability
import org.jarsi.arkphone.telecom.WhatsAppCallLauncher
import org.jarsi.arkphone.telecom.WhatsAppCaller
import org.jarsi.arkphone.util.AndroidNotificationAccessChecker
import org.jarsi.arkphone.util.AndroidPermissionChecker
import org.jarsi.arkphone.util.Clock
import org.jarsi.arkphone.util.AndroidToaster
import org.jarsi.arkphone.util.NotificationAccessChecker
import org.jarsi.arkphone.util.PermissionChecker
import org.jarsi.arkphone.util.Toaster
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
    abstract fun bindContactsRepository(impl: SystemContactsRepository): ContactsRepository

    @Binds
    @Singleton
    abstract fun bindPermissionChecker(impl: AndroidPermissionChecker): PermissionChecker

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: DataStoreSettingsRepository): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindMessagesRepository(impl: SystemMessagesRepository): MessagesRepository

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
    abstract fun bindSpeechAvailability(impl: AndroidSpeechAvailability): SpeechAvailability

    @Binds
    @Singleton
    abstract fun bindSimAccountRepository(
        impl: TelecomSimAccountRepository,
    ): SimAccountRepository

    @Binds
    @Singleton
    abstract fun bindTelecomCallPlacer(impl: AndroidTelecomCallPlacer): TelecomCallPlacer

    // The app's own list, not the platform's: the platform rejects its
    // entries before the app sees the call, so the reject-or-voicemail
    // choice could never apply to them.
    @Binds
    @Singleton
    abstract fun bindBlockedNumbersRepository(
        impl: DataStoreBlockedNumbersRepository,
    ): BlockedNumbersRepository

    @Binds
    @Singleton
    abstract fun bindSystemBlockedNumberList(
        impl: AndroidBlockedNumbersRepository,
    ): SystemBlockedNumberList

    @Binds
    @Singleton
    abstract fun bindNotificationAccessChecker(
        impl: AndroidNotificationAccessChecker,
    ): NotificationAccessChecker

    @Binds
    @Singleton
    abstract fun bindWhatsAppCallLogRepository(
        impl: RoomWhatsAppCallLogRepository,
    ): WhatsAppCallLogRepository

    @Binds
    @Singleton
    abstract fun bindWhatsAppCallLauncher(impl: WhatsAppCaller): WhatsAppCallLauncher

    @Binds
    @Singleton
    abstract fun bindWhatsAppAvailability(impl: WhatsAppCaller): WhatsAppAvailability

    @Binds
    @Singleton
    abstract fun bindSpeedDialRepository(impl: DataStoreSpeedDialRepository): SpeedDialRepository

    @Binds
    @Singleton
    abstract fun bindRingSilencer(impl: AndroidRingSilencer): RingSilencer

    @Binds
    @Singleton
    abstract fun bindProximityLock(impl: AndroidProximityLock): ProximityLock

    @Binds
    @Singleton
    abstract fun bindCallScreeningRole(impl: AndroidCallScreeningRole): CallScreeningRole

    @Binds
    @Singleton
    abstract fun bindRejectMessageSender(impl: SmsRejectMessageSender): RejectMessageSender

    @Binds
    @Singleton
    abstract fun bindToaster(impl: AndroidToaster): Toaster

    @Binds
    @Singleton
    abstract fun bindSmsSender(impl: AndroidSmsSender): SmsSender

    @Binds
    @Singleton
    abstract fun bindMessageNotifier(impl: AndroidMessageNotifier): MessageNotifier

    @Binds
    @Singleton
    abstract fun bindMmsSender(impl: AndroidMmsSender): MmsSender

    @Binds
    @Singleton
    abstract fun bindMessageSharer(impl: AndroidMessageSharer): MessageSharer

    @Binds
    @Singleton
    abstract fun bindMmsTransport(impl: PlatformMmsTransport): MmsTransport

    @Binds
    @Singleton
    abstract fun bindSmsRole(impl: AndroidSmsRole): SmsRole

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

        @Provides
        @Singleton
        fun provideCallLogRepository(
            system: SystemCallLogRepository,
            whatsApp: WhatsAppCallLogRepository,
        ): CallLogRepository = CombinedCallLogRepository(system, whatsApp)

        @Provides
        @Singleton
        fun provideDatabase(@ApplicationContext context: Context): ArkPhoneDatabase =
            Room.databaseBuilder(context, ArkPhoneDatabase::class.java, "arkphone.db")
                .addMigrations(ArkPhoneDatabase.MIGRATION_1_2)
                .build()

        @Provides
        fun provideWhatsAppCallDao(db: ArkPhoneDatabase): WhatsAppCallDao = db.whatsAppCallDao()
    }
}
