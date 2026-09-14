package com.sma.atsvslog.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DiagnosticsViewModel(
    private val repository: DiagnosticsRepository
) : ViewModel() {

    private val _snapshot = MutableStateFlow<DiagnosticsSnapshot?>(null)
    val snapshot: StateFlow<DiagnosticsSnapshot?> = _snapshot.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _snapshot.value = repository.snapshot()
        }
    }

    fun clearRecentLogs() {
        viewModelScope.launch {
            repository.clearRecentLogs()
            refresh()
        }
    }

    class Factory(
        private val repository: DiagnosticsRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(DiagnosticsViewModel::class.java)) {
                return DiagnosticsViewModel(repository) as T
            }
            throw IllegalArgumentException(
                "Unknown ViewModel class: ${modelClass.name}"
            )
        }
    }
}
