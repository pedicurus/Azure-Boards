package com.example.ui.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.WorkItem
import com.example.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The board's uiState is built from 30 flows read back by array index. This test runs the real
 * combine so a wrong index (a ClassCastException at runtime) fails CI instead of the app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorkItemsUiStateTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        AppContainer.resetForTesting()
        val application = ApplicationProvider.getApplicationContext<Application>()
        container = AppContainer.getInstance(application)
        container.repository.clearCredentials()
    }

    @After
    fun tearDown() {
        container.repository.clearCredentials()
        AppContainer.resetForTesting()
        Dispatchers.resetMain()
    }

    @Test
    fun `uiState maps every combined input to the right field`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = container.provideViewModelFactory(application).create(WorkItemsViewModel::class.java)
        val collector = CoroutineScope(testDispatcher).launch { viewModel.uiState.collect { } }

        viewModel.setSearchQuery("needle")
        assertEquals("needle", viewModel.uiState.value.searchQuery)

        val item = WorkItem(id = 7, title = "Fix login", workItemType = "Bug", state = "Active", teamProject = "Mobile")
        viewModel.selectWorkItem(item)
        assertEquals(7, viewModel.uiState.value.selectedWorkItem?.id)
        assertEquals(false, viewModel.uiState.value.isLoadingMore)
        assertEquals(false, viewModel.uiState.value.isPullRefreshing)

        collector.cancel()
    }
}
