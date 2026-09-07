package com.sma.atsvslog.features.sale

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sma.atsvslog.repository.CloudMasterRepository
import com.sma.atsvslog.repository.CloudModelOwnershipResult
import com.sma.atsvslog.repository.LocalSalesRepository
import com.sma.atsvslog.repository.SaleItemDraft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
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
    val errorMessage: String? = null,
    val modelConflict: ModelConflictPrompt? = null,
    val focusModelRequest: Int = 0,
    val editingConflictLocalId: Long? = null
)

data class ModelConflictPrompt(
    val model: String,
    val requestedType: String,
    val requestedBrand: String,
    val canonicalType: String,
    val canonicalBrand: String
)

class SaleRecorderViewModel(
    private val repository: LocalSalesRepository,
    private val cloudMasterRepository: CloudMasterRepository,
    private val date: String,
    private val conflictLocalId: Long? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SaleRecorderUiState(date = date)
    )

    val uiState: StateFlow<SaleRecorderUiState> = _uiState.asStateFlow()

    private val typeFlow = MutableStateFlow("")
    private val brandFlow = MutableStateFlow("")

    init {
        viewModelScope.launch {
            runCatching {
                if (conflictLocalId == null) {
                    val transactionUuid = repository.startTransaction(date)
                    _uiState.update {
                        it.copy(transactionUuid = transactionUuid)
                    }
                } else {
                    val edit = repository.prepareConflictSaleForEditing(conflictLocalId)
                    typeFlow.value = edit.conflict.canonicalType
                    brandFlow.value = edit.conflict.canonicalBrand
                    _uiState.update {
                        it.copy(
                            transactionUuid = edit.conflict.transactionUuid,
                            itemsSaved = edit.itemCount,
                            type = edit.conflict.canonicalType,
                            brand = edit.conflict.canonicalBrand,
                            model = edit.item.model,
                            size = edit.item.size,
                            colour = edit.item.colour,
                            sellingPrice = edit.item.sellingPrice.toString(),
                            editingConflictLocalId = edit.conflict.localId,
                            focusModelRequest = it.focusModelRequest + 1
                        )
                    }
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(errorMessage = error.message ?: "Unable to open the conflicting sale.")
                }
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
            combine(typeFlow, brandFlow) { type, brand ->
                type to brand
            }
                .distinctUntilChanged()
                .flatMapLatest { (type, brand) ->
                    if (type.isBlank() || type == ENTER_NEW || brand.isBlank()) {
                        kotlinx.coroutines.flow.flowOf(emptyList())
                    } else {
                        repository.observeModels(type, brand)
                    }
                }
                .collect { values ->
                    _uiState.update {
                        it.copy(models = values)
                    }
                }
        }

        viewModelScope.launch {
            _uiState
                .map { it.model }
                .distinctUntilChanged()
                .flatMapLatest { model ->
                    if (model.isBlank() || model == ENTER_NEW) {
                        kotlinx.coroutines.flow.flowOf(emptyList())
                    } else {
                        repository.observeSizes(model)
                    }
                }
                .collect { values ->
                    _uiState.update {
                        it.copy(sizes = values)
                    }
                }
        }

        viewModelScope.launch {
            _uiState
                .map { it.model }
                .distinctUntilChanged()
                .flatMapLatest { model ->
                    if (model.isBlank() || model == ENTER_NEW) {
                        kotlinx.coroutines.flow.flowOf(emptyList())
                    } else {
                        repository.observeColours(model)
                    }
                }
                .collect { values ->
                    _uiState.update {
                        it.copy(colours = values)
                    }
                }
        }
    }

    fun onTypeSelected(value: String) {
        typeFlow.value = if (value == ENTER_NEW) "" else value

        _uiState.update {
            it.copy(
                type = value,
                customType = "",
                model = "",
                size = "Not Specified",
                colour = "",
                customModel = "",
                customSize = "",
                customColour = "",
                sellingPrice = "",
                models = emptyList(),
                sizes = emptyList(),
                colours = emptyList()
            )
        }
    }

    fun onCustomTypeChanged(value: String) {
        typeFlow.value = ""
        _uiState.update {
            it.copy(
                type = ENTER_NEW,
                customType = value,
                model = "",
                size = "Not Specified",
                colour = "",
                customModel = "",
                customSize = "",
                customColour = "",
                sellingPrice = "",
                models = emptyList(),
                sizes = emptyList(),
                colours = emptyList()
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
                sizes = emptyList(),
                colours = emptyList()
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

            val draft = SaleItemDraft(
                type = effectiveType,
                brand = state.brand,
                model = effectiveModel,
                size = effectiveSize,
                colour = effectiveColour,
                sellingPrice = state.sellingPrice.toLong()
            )

            if (state.editingConflictLocalId != null) {
                runCatching {
                    repository.updateConflictSaleItem(
                        conflictLocalId = state.editingConflictLocalId,
                        draft = draft
                    )
                }.onSuccess {
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            errorMessage = null,
                            type = effectiveType,
                            model = effectiveModel,
                            size = effectiveSize,
                            colour = effectiveColour,
                            customType = "",
                            customModel = "",
                            customSize = "",
                            customColour = "",
                            sellingPrice = draft.sellingPrice.toString()
                        )
                    }
                }.onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            errorMessage = error.message ?: "Unable to save the corrected sale item."
                        )
                    }
                }
                return@launch
            }

            if (state.model == ENTER_NEW) {
                when (
                    val ownership = cloudMasterRepository.checkModelOwnership(
                        model = effectiveModel,
                        requestedType = effectiveType,
                        requestedBrand = state.brand
                    )
                ) {
                    is CloudModelOwnershipResult.Conflict -> {
                        _uiState.update {
                            it.copy(
                                isSaving = false,
                                modelConflict = ModelConflictPrompt(
                                    model = ownership.model,
                                    requestedType = effectiveType,
                                    requestedBrand = state.brand,
                                    canonicalType = ownership.canonicalType,
                                    canonicalBrand = ownership.canonicalBrand
                                )
                            )
                        }
                        return@launch
                    }

                    CloudModelOwnershipResult.NoConflict -> {
                        saveDraft(
                            draft,
                            allowModelConflict = false,
                            trustCloudOwnership = true
                        )
                    }

                    CloudModelOwnershipResult.Unavailable -> {
                        saveDraft(draft, allowModelConflict = true)
                    }
                }
            } else {
                saveDraft(draft, allowModelConflict = false)
            }
        }
    }

    fun onModelConflictDiscard() {
        val state = _uiState.value
        val conflict = state.modelConflict ?: return
        val transactionUuid = state.transactionUuid ?: return

        val effectiveSize =
            if (state.size == ENTER_NEW) state.customSize else state.size
        val effectiveColour =
            if (state.colour == ENTER_NEW) state.customColour else state.colour

        if (effectiveSize.isBlank() || effectiveColour.isBlank() ||
            state.sellingPrice.toLongOrNull() == null
        ) {
            _uiState.update {
                it.copy(
                    modelConflict = null,
                    errorMessage = "Complete the item details before resolving the Model conflict."
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(isSaving = true, modelConflict = null, errorMessage = null)
            }

            runCatching {
                repository.reclassifyAndSaveItem(
                    transactionUuid = transactionUuid,
                    draft = SaleItemDraft(
                        type = conflict.requestedType,
                        brand = conflict.requestedBrand,
                        model = conflict.model,
                        size = effectiveSize,
                        colour = effectiveColour,
                        sellingPrice = state.sellingPrice.toLong()
                    ),
                    canonicalType = conflict.canonicalType,
                    canonicalBrand = conflict.canonicalBrand
                )
            }.onSuccess {
                clearAfterItemSaved()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = error.message ?: "Unable to resolve Model conflict."
                    )
                }
            }
        }
    }

    fun onModelConflictCancel() {
        _uiState.update {
            it.copy(
                modelConflict = null,
                focusModelRequest = it.focusModelRequest + 1,
                isSaving = false
            )
        }
    }

    private suspend fun saveDraft(
        draft: SaleItemDraft,
        allowModelConflict: Boolean,
        trustCloudOwnership: Boolean = false
    ) {
        val transactionUuid = _uiState.value.transactionUuid ?: return

        runCatching {
            repository.saveItem(
                transactionUuid = transactionUuid,
                draft = draft,
                allowModelConflict = allowModelConflict,
                trustCloudOwnership = trustCloudOwnership
            )
        }.onSuccess {
            clearAfterItemSaved()
        }.onFailure { error ->
            _uiState.update {
                it.copy(
                    isSaving = false,
                    errorMessage = error.message ?: "Unable to save item."
                )
            }
        }
    }

    private fun clearAfterItemSaved() {
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
                modelConflict = null,
                errorMessage = null
            )
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

            val editConflictId = state.editingConflictLocalId

            runCatching {
                if (editConflictId != null) {
                    val effectiveType = if (state.type == ENTER_NEW) state.customType else state.type
                    val effectiveModel = if (state.model == ENTER_NEW) state.customModel else state.model
                    val effectiveSize = if (state.size == ENTER_NEW) state.customSize else state.size
                    val effectiveColour = if (state.colour == ENTER_NEW) state.customColour else state.colour
                    repository.finishConflictSaleEdit(
                        conflictLocalId = editConflictId,
                        draft = SaleItemDraft(
                            type = effectiveType,
                            brand = state.brand,
                            model = effectiveModel,
                            size = effectiveSize,
                            colour = effectiveColour,
                            sellingPrice = state.sellingPrice.toLong()
                        )
                    )
                } else {
                    repository.finishCustomer(transactionUuid)
                }
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
        private val cloudMasterRepository: CloudMasterRepository,
        private val date: String,
        private val conflictLocalId: Long? = null
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
                    repository = repository,
                    cloudMasterRepository = cloudMasterRepository,
                    date = date,
                    conflictLocalId = conflictLocalId
                ) as T
            }

            throw IllegalArgumentException(
                "Unknown ViewModel class: ${modelClass.name}"
            )
        }
    }
}

private const val ENTER_NEW = "Enter New"