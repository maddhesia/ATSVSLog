package com.sma.atsvslog.features.sale

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sma.atsvslog.repository.LocalSalesRepository
import com.sma.atsvslog.repository.SaleItemDraft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SaleRecorderUiState(
    val date: String,
    val transactionUuid: String? = null,
    val itemsSaved: Int = 0,
    val types: List<String> = emptyList(),
    val models: List<String> = emptyList(),
    val sizes: List<String> = emptyList(),
    val colours: List<String> = emptyList(),

    val type: String = "",
    val brand: String = "",
    val model: String = "",
    val size: String = "Not Specified",
    val colour: String = "",

    val customType: String = "",
    val customModel: String = "",
    val customSize: String = "",
    val customColour: String = "",

    val sellingPrice: String = "",
    val isSaving: Boolean = false,
    val isFinished: Boolean = false,
    val errorMessage: String? = null
)

class SaleRecorderViewModel(
    private val repository: LocalSalesRepository,
    private val date: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SaleRecorderUiState(date = date)
    )

    val uiState: StateFlow<SaleRecorderUiState> = _uiState.asStateFlow()

    private val brandFlow = MutableStateFlow("")

    init {
        viewModelScope.launch {
            val transactionUuid = repository.startTransaction(date)
            _uiState.update {
                it.copy(transactionUuid = transactionUuid)
            }
        }

        viewModelScope.launch {
            repository.observeTypes().collect { values ->
                _uiState.update {
                    it.copy(types = values)
                }
            }
        }

        viewModelScope.launch {
            brandFlow
                .flatMapLatest { brand ->
                    if (brand.isBlank()) {
                        kotlinx.coroutines.flow.flowOf(emptyList())
                    } else {
                        repository.observeModels(brand)
                    }
                }
                .collect { values ->
                    _uiState.update {
                        it.copy(models = values)
                    }
                }
        }

        viewModelScope.launch {
            combine(brandFlow, _uiState) { brand, state ->
                brand to state.model
            }
                .distinctUntilChanged()
                .flatMapLatest { (brand, model) ->
                    if (
                        brand.isBlank() ||
                        model.isBlank() ||
                        model == ENTER_NEW
                    ) {
                        kotlinx.coroutines.flow.flowOf(emptyList())
                    } else {
                        repository.observeSizes(brand, model)
                    }
                }
                .collect { values ->
                    _uiState.update {
                        it.copy(sizes = values)
                    }
                }
        }

        viewModelScope.launch {
            repository.observeColours().collect { values ->
                _uiState.update {
                    it.copy(colours = values)
                }
            }
        }
    }

    fun onTypeSelected(value: String) {
        if (value == ENTER_NEW) {
            _uiState.update {
                it.copy(
                    type = ENTER_NEW,
                    customType = ""
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    type = value,
                    customType = ""
                )
            }
        }
    }

    fun onCustomTypeChanged(value: String) {
        _uiState.update {
            it.copy(
                type = ENTER_NEW,
                customType = value
            )
        }
    }

    fun onBrandSelected(value: String) {
        brandFlow.value = value

        _uiState.update {
            it.copy(
                brand = value,
                model = "",
                size = "Not Specified",
                colour = "",
                customModel = "",
                customSize = "",
                customColour = "",
                sellingPrice = "",
                models = emptyList(),
                sizes = emptyList()
            )
        }
    }

    fun onModelSelected(value: String) {
        if (value == ENTER_NEW) {
            _uiState.update {
                it.copy(
                    model = ENTER_NEW,
                    customModel = "",
                    size = "Not Specified",
                    customSize = "",
                    sellingPrice = ""
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    model = value,
                    customModel = "",
                    size = "Not Specified",
                    customSize = "",
                    sellingPrice = ""
                )
            }

            refreshSuggestedPrice()
        }
    }

    fun onCustomModelChanged(value: String) {
        _uiState.update {
            it.copy(
                model = ENTER_NEW,
                customModel = value,
                size = "Not Specified",
                customSize = "",
                sellingPrice = ""
            )
        }
    }

    fun onSizeSelected(value: String) {
        if (value == ENTER_NEW) {
            _uiState.update {
                it.copy(
                    size = ENTER_NEW,
                    customSize = "",
                    sellingPrice = ""
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    size = value,
                    customSize = "",
                    sellingPrice = ""
                )
            }

            refreshSuggestedPrice()
        }
    }

    fun onCustomSizeChanged(value: String) {
        _uiState.update {
            it.copy(
                size = ENTER_NEW,
                customSize = value,
                sellingPrice = ""
            )
        }
    }

    fun onColourSelected(value: String) {
        if (value == ENTER_NEW) {
            _uiState.update {
                it.copy(
                    colour = ENTER_NEW,
                    customColour = ""
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    colour = value,
                    customColour = ""
                )
            }

            refreshSuggestedPrice()
        }
    }

    fun onCustomColourChanged(value: String) {
        _uiState.update {
            it.copy(
                colour = ENTER_NEW,
                customColour = value
            )
        }
    }

    fun onSellingPriceChanged(value: String) {
        _uiState.update {
            it.copy(
                sellingPrice = value.filter(Char::isDigit)
            )
        }
    }

    fun saveItem() {
        val state = _uiState.value
        val transactionUuid = state.transactionUuid ?: return

        val effectiveType =
            if (state.type == ENTER_NEW) state.customType else state.type

        val effectiveModel =
            if (state.model == ENTER_NEW) state.customModel else state.model

        val effectiveSize =
            if (state.size == ENTER_NEW) state.customSize else state.size

        val effectiveColour =
            if (state.colour == ENTER_NEW) state.customColour else state.colour

        val validationError = validate(
            state = state,
            effectiveType = effectiveType,
            effectiveModel = effectiveModel,
            effectiveSize = effectiveSize,
            effectiveColour = effectiveColour
        )

        if (validationError != null) {
            _uiState.update {
                it.copy(errorMessage = validationError)
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSaving = true,
                    errorMessage = null
                )
            }

            runCatching {
                repository.saveItem(
                    transactionUuid = transactionUuid,
                    draft = SaleItemDraft(
                        type = effectiveType,
                        brand = state.brand,
                        model = effectiveModel,
                        size = effectiveSize,
                        colour = effectiveColour,
                        sellingPrice = state.sellingPrice.toLong()
                    )
                )
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        itemsSaved = it.itemsSaved + 1,
                        isSaving = false,
                        model = "",
                        size = "Not Specified",
                        colour = "",
                        customModel = "",
                        customSize = "",
                        customColour = "",
                        sellingPrice = "",
                        errorMessage = null
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage =
                            error.message ?: "Unable to save item."
                    )
                }
            }
        }
    }

    fun finishCustomer(onFinished: () -> Unit) {
        val state = _uiState.value
        val transactionUuid = state.transactionUuid ?: return

        if (state.itemsSaved == 0) {
            _uiState.update {
                it.copy(
                    errorMessage =
                        "Save at least one item before finishing."
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSaving = true,
                    errorMessage = null
                )
            }

            runCatching {
                repository.finishCustomer(transactionUuid)
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        isFinished = true
                    )
                }
                onFinished()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage =
                            error.message ?: "Unable to finish customer."
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update {
            it.copy(errorMessage = null)
        }
    }

    private fun refreshSuggestedPrice() {
        val state = _uiState.value

        if (
            state.model.isBlank() ||
            state.model == ENTER_NEW ||
            state.colour.isBlank() ||
            state.colour == ENTER_NEW
        ) {
            return
        }

        viewModelScope.launch {
            val price = repository.findLastSellingPrice(
                model = state.model,
                size = state.size,
                colour = state.colour
            )

            if (
                price != null &&
                _uiState.value.sellingPrice.isBlank()
            ) {
                _uiState.update {
                    it.copy(
                        sellingPrice = price.toString()
                    )
                }
            }
        }
    }

    private fun validate(
        state: SaleRecorderUiState,
        effectiveType: String,
        effectiveModel: String,
        effectiveSize: String,
        effectiveColour: String
    ): String? {

        if (effectiveType.isBlank()) {
            return "Select or enter a Type."
        }

        if (state.brand.isBlank()) {
            return "Select a Brand."
        }

        if (effectiveModel.isBlank()) {
            return "Select or enter a Model."
        }

        if (effectiveSize.isBlank()) {
            return "Select or enter a Size."
        }

        if (effectiveColour.isBlank()) {
            return "Select or enter a Colour."
        }

        if (state.sellingPrice.isBlank()) {
            return "Enter the Selling Price."
        }

        if (state.sellingPrice.toLongOrNull() == null) {
            return "Selling Price must be a valid number."
        }

        return null
    }

    class Factory(
        private val repository: LocalSalesRepository,
        private val date: String
    ) : ViewModelProvider.Factory {

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(
            modelClass: Class<T>
        ): T {
            if (
                modelClass.isAssignableFrom(
                    SaleRecorderViewModel::class.java
                )
            ) {
                return SaleRecorderViewModel(
                    repository,
                    date
                ) as T
            }

            throw IllegalArgumentException(
                "Unknown ViewModel class: ${modelClass.name}"
            )
        }
    }
}

private const val ENTER_NEW = "Enter New"