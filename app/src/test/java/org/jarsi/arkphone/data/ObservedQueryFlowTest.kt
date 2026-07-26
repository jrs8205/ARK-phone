package org.jarsi.arkphone.data

import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ObservedQueryFlowTest {

    private val refreshSignal = MutableSharedFlow<Unit>()
    private var permissionGranted = false
    private var data = listOf<String>()
    private var registerCalls = 0
    private var unregisterCalls = 0
    private var notifyChange: (() -> Unit)? = null

    private fun flowUnderTest() = observedQueryFlow(
        hasPermission = { permissionGranted },
        registerObserver = { onChange ->
            registerCalls++
            notifyChange = onChange
        },
        unregisterObserver = { unregisterCalls++ },
        refreshSignal = refreshSignal,
        query = { if (permissionGranted) data else emptyList() },
    )

    @Test
    fun emitsInitialQueryResultAndRegistersObserverWhenPermitted() = runTest {
        permissionGranted = true
        data = listOf("a")
        flowUnderTest().test {
            assertEquals(listOf("a"), awaitItem())
            assertEquals(1, registerCalls)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun doesNotRegisterObserverWithoutPermission() = runTest {
        flowUnderTest().test {
            assertEquals(emptyList<String>(), awaitItem())
            assertEquals(0, registerCalls)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun refreshAfterGrantRegistersObserverAndRequeries() = runTest {
        flowUnderTest().test {
            assertEquals(emptyList<String>(), awaitItem())
            permissionGranted = true
            data = listOf("a")
            refreshSignal.emit(Unit)
            assertEquals(listOf("a"), awaitItem())
            assertEquals(1, registerCalls)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun providerChangeNotificationRequeries() = runTest {
        permissionGranted = true
        data = listOf("a")
        flowUnderTest().test {
            assertEquals(listOf("a"), awaitItem())
            assertNotNull(notifyChange)
            data = listOf("a", "b")
            notifyChange?.invoke()
            assertEquals(listOf("a", "b"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun refreshWithoutPermissionRequeriesButDoesNotRegister() = runTest {
        flowUnderTest().test {
            assertEquals(emptyList<String>(), awaitItem())
            refreshSignal.emit(Unit)
            assertEquals(emptyList<String>(), awaitItem())
            assertEquals(0, registerCalls)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun refreshAfterRevokeEmitsEmptyWithoutUnregisterCrash() = runTest {
        permissionGranted = true
        data = listOf("a")
        flowUnderTest().test {
            assertEquals(listOf("a"), awaitItem())
            permissionGranted = false
            refreshSignal.emit(Unit)
            assertEquals(emptyList<String>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, unregisterCalls)
    }

    @Test
    fun closeUnregistersOnlyWhenObserverWasRegistered() = runTest {
        flowUnderTest().test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(0, unregisterCalls)

        permissionGranted = true
        flowUnderTest().test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, unregisterCalls)
    }

    @Test
    fun observerRegistersOnlyOnceAcrossRepeatedRefreshes() = runTest {
        permissionGranted = true
        flowUnderTest().test {
            awaitItem()
            refreshSignal.emit(Unit)
            awaitItem()
            refreshSignal.emit(Unit)
            awaitItem()
            assertEquals(1, registerCalls)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
