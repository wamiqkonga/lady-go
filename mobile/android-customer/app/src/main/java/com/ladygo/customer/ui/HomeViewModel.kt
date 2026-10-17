package com.ladygo.customer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ladygo.customer.BuildConfig
import com.ladygo.customer.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Placeholder home until the booking flow (next slice) lands; for now it only proves the session
 *  works end to end and offers log out. */
@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val authRepository: AuthRepository,
    ) : ViewModel() {
        val apiBaseUrl: String = BuildConfig.API_BASE_URL

        fun onLogout() {
            viewModelScope.launch { authRepository.logout() }
        }
    }
