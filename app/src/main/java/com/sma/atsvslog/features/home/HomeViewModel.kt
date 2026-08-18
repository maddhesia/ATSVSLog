package com.sma.atsvslog.features.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sma.atsvslog.repository.LocalSalesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val walkIns: Int = 0,
    val conversions: Int = 0
)

class HomeViewModel(
    private val repository: LocalSalesRepository,
    private val date: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeDailyCounter(date).collect { counter ->
                _uiState.update {
                    it.copy(
                        walkIns = counter?.walkIns ?: 0,
                        conversions = counter?.conversions ?: 0
                    )
                }
            }
        }
    }

    fun addWalkIn() {
        viewModelScope.launch { repository.addWalkIn(date) }
    }

    fun removeWalkIn() {
        viewModelScope.launch { repository.removeWalkIn(date) }
    }

    fun resetWalkIns() {
        viewModelScope.launch { repository.resetWalkIns(date) }
    }

    class Factory(
        private val repository: LocalSalesRepository,
        private val date: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                return HomeViewModel(repository, date) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
