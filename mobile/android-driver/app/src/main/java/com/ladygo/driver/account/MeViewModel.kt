package com.ladygo.driver.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ladygo.driver.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The Me tab's actions. Log out is the same call Home's button makes; the nav host sees the session end and goes to Login. */
@HiltViewModel
class MeViewModel
    @Inject
    constructor(
        private val authRepository: AuthRepository,
    ) : ViewModel() {
        fun onLogout() {
            viewModelScope.launch { authRepository.logout() }
        }
    }
