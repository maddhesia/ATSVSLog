package com.sma.atsvslog.features.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sma.atsvslog.network.AtSvsApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ReportUiState(
    val isLoading: Boolean = true,
    val report: DailyReport? = null,
    val errorMessage: String? = null
)

class ReportViewModel(
    private val repository: ReportRepository,
    private val date: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReportUiState())
    val uiState: StateFlow<ReportUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun reload() {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.value = ReportUiState(isLoading = true)

            runCatching {
                repository.fetchReport(date)
            }.onSuccess { report ->
                _uiState.value = ReportUiState(
                    isLoading = false,
                    report = report
                )
            }.onFailure { error ->
                _uiState.value = ReportUiState(
                    isLoading = false,
                    errorMessage =
                        error.message ?: "Unable to load report."
                )
            }
        }
    }

    class Factory(
        private val api: AtSvsApi,
        private val date: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(
            modelClass: Class<T>
        ): T {
            if (
                modelClass.isAssignableFrom(
                    ReportViewModel::class.java
                )
            ) {
                return ReportViewModel(
                    repository = ReportRepository(api),
                    date = date
                ) as T
            }

            throw IllegalArgumentException(
                "Unknown ViewModel class: ${modelClass.name}"
            )
        }
    }
}
