package com.sma.atsvslog.features.sale

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MenuAnchorType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaleRecorderScreen(
    state: SaleRecorderUiState,
    onTypeSelected: (String) -> Unit,
    onCustomTypeChanged: (String) -> Unit,
    onBrandSelected: (String) -> Unit,
    onModelSelected: (String) -> Unit,
    onCustomModelChanged: (String) -> Unit,
    onSizeSelected: (String) -> Unit,
    onCustomSizeChanged: (String) -> Unit,
    onColourSelected: (String) -> Unit,
    onCustomColourChanged: (String) -> Unit,
    onSellingPriceChanged: (String) -> Unit,
    onSaveItem: () -> Unit,
    onFinishCustomer: () -> Unit,
    onClearError: () -> Unit
) {
    var typeExpanded by remember { mutableStateOf(false) }
    var brandExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var sizeExpanded by remember { mutableStateOf(false) }
    var colourExpanded by remember { mutableStateOf(false) }

    val typeIsCustom = state.type == ENTER_NEW
    val modelIsCustom = state.model == ENTER_NEW
    val sizeIsCustom = state.size == ENTER_NEW
    val colourIsCustom = state.colour == ENTER_NEW

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Record Sale",
            style = MaterialTheme.typography.headlineSmall
        )

        Text(
            text = "Date: ${state.date}",
            style = MaterialTheme.typography.bodyMedium
        )

        if (state.itemsSaved > 0) {
            Text(
                text = "${state.itemsSaved} item${if (state.itemsSaved == 1) "" else "s"} saved for this customer.",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        DropdownField(
            label = "Type",
            value = if (typeIsCustom) state.customType else state.type,
            expanded = typeExpanded,
            onExpandedChange = {
                typeExpanded = !typeExpanded
            },
            options = (
                listOf(
                    "Trolley Bag",
                    "Duffel Bag",
                    "Backpack"
                ) + state.types + ENTER_NEW
            ).distinct(),
            onOptionSelected = {
                onTypeSelected(it)
                typeExpanded = false
            }
        )

        if (typeIsCustom) {
            OutlinedTextField(
                value = state.customType,
                onValueChange = onCustomTypeChanged,
                label = { Text("Enter new Type") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        DropdownField(
            label = "Brand",
            value = state.brand,
            expanded = brandExpanded,
            onExpandedChange = {
                brandExpanded = !brandExpanded
            },
            options = listOf(
                "American Tourister",
                "Kamiliant",
                "Samsonite"
            ),
            disabledOptions = setOf("Samsonite"),
            onOptionSelected = {
                onBrandSelected(it)
                brandExpanded = false
            }
        )

        DropdownField(
            label = "Model",
            value = if (modelIsCustom) state.customModel else state.model,
            expanded = modelExpanded,
            onExpandedChange = {
                modelExpanded = !modelExpanded
            },
            options = (state.models + ENTER_NEW).distinct(),
            onOptionSelected = {
                onModelSelected(it)
                modelExpanded = false
            }
        )

        if (modelIsCustom) {
            OutlinedTextField(
                value = state.customModel,
                onValueChange = onCustomModelChanged,
                label = { Text("Enter new Model") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        DropdownField(
            label = "Size",
            value = if (sizeIsCustom) state.customSize else state.size,
            expanded = sizeExpanded,
            onExpandedChange = {
                sizeExpanded = !sizeExpanded
            },
            options = (
                    listOf("Not Specified") +
                            state.sizes +
                            ENTER_NEW
                    ).distinct(),
            onOptionSelected = {
                onSizeSelected(it)
                sizeExpanded = false
            }
        )

        if (sizeIsCustom) {
            OutlinedTextField(
                value = state.customSize,
                onValueChange = onCustomSizeChanged,
                label = { Text("Enter new Size") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        DropdownField(
            label = "Colour",
            value = if (colourIsCustom) state.customColour else state.colour,
            expanded = colourExpanded,
            onExpandedChange = {
                colourExpanded = !colourExpanded
            },
            options = (state.colours + ENTER_NEW).distinct(),
            onOptionSelected = {
                onColourSelected(it)
                colourExpanded = false
            }
        )

        if (colourIsCustom) {
            OutlinedTextField(
                value = state.customColour,
                onValueChange = onCustomColourChanged,
                label = { Text("Enter new Colour") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        OutlinedTextField(
            value = state.sellingPrice,
            onValueChange = onSellingPriceChanged,
            label = { Text("Selling Price") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number
            )
        )

        if (!state.errorMessage.isNullOrBlank()) {
            Text(
                text = state.errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )

            TextButton(onClick = onClearError) {
                Text("Dismiss")
            }
        }

        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onSaveItem,
                enabled = !state.isSaving,
                modifier = Modifier.weight(1f)
            ) {
                Text("SAVE ITEM")
            }

            Button(
                onClick = onFinishCustomer,
                enabled = !state.isSaving && state.itemsSaved > 0,
                modifier = Modifier.weight(1f)
            ) {
                Text("FINISH CUSTOMER")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(
    label: String,
    value: String,
    expanded: Boolean,
    onExpandedChange: () -> Unit,
    options: List<String>,
    onOptionSelected: (String) -> Unit,
    disabledOptions: Set<String> = emptySet()
) {
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = {
            onExpandedChange()
        }
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(
                    expanded = expanded
                )
            },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = onExpandedChange
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onOptionSelected(option)
                    },
                    enabled = option !in disabledOptions
                )
            }
        }
    }
}

private const val ENTER_NEW = "Enter New"