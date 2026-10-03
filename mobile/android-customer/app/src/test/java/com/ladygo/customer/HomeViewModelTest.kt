package com.ladygo.customer

import com.ladygo.customer.ui.HomeViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeViewModelTest {
    @Test
    fun exposesConfiguredApiBaseUrl() {
        val vm = HomeViewModel(apiBaseUrl = "https://api.test.example")
        assertEquals("https://api.test.example", vm.uiState.value.apiBaseUrl)
    }

    @Test
    fun skeletonNoteIsPresent() {
        val vm = HomeViewModel(apiBaseUrl = "http://localhost")
        assertTrue(vm.uiState.value.phaseNote.isNotBlank())
    }
}
