package com.sma.atsvslog

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sma.atsvslog.di.DatabaseProvider
import com.sma.atsvslog.features.home.HomeScreen
import com.sma.atsvslog.features.home.HomeViewModel
import com.sma.atsvslog.features.sale.SaleRecorderScreen
import com.sma.atsvslog.features.sale.SaleRecorderViewModel
import com.sma.atsvslog.repository.LocalSalesRepository
import com.sma.atsvslog.ui.ui.ATSVSLogTheme
import java.time.LocalDate

class MainActivity : ComponentActivity() {

    private val database by lazy { DatabaseProvider.get(applicationContext) }
    private val salesRepository by lazy { LocalSalesRepository(database) }

    private var selectedDate by mutableStateOf(LocalDate.now().toString())
    private var showSaleRecorder by mutableStateOf(false)
    private var saleSessionId by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContent {
            ATSVSLogTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BackHandler(enabled = showSaleRecorder) {
                        showSaleRecorder = false
                    }

                    if (showSaleRecorder) {
                        val saleViewModel: SaleRecorderViewModel = viewModel(
                            key = "sale-$selectedDate-$saleSessionId",
                            factory = SaleRecorderViewModel.Factory(
                                repository = salesRepository,
                                date = selectedDate
                            )
                        )
                        val state by saleViewModel.uiState.collectAsStateWithLifecycle()

                        SaleRecorderScreen(
                            state = state,

                            onTypeSelected = saleViewModel::onTypeSelected,
                            onCustomTypeChanged = saleViewModel::onCustomTypeChanged,

                            onBrandSelected = saleViewModel::onBrandSelected,

                            onModelSelected = saleViewModel::onModelSelected,
                            onCustomModelChanged = saleViewModel::onCustomModelChanged,

                            onSizeSelected = saleViewModel::onSizeSelected,
                            onCustomSizeChanged = saleViewModel::onCustomSizeChanged,

                            onColourSelected = saleViewModel::onColourSelected,
                            onCustomColourChanged = saleViewModel::onCustomColourChanged,

                            onSellingPriceChanged = saleViewModel::onSellingPriceChanged,

                            onSaveItem = saleViewModel::saveItem,

                            onFinishCustomer = {
                                saleViewModel.finishCustomer {
                                    showSaleRecorder = false
                                }
                            },

                            onClearError = saleViewModel::clearError
                        )
                    } else {
                        val counterViewModel: HomeViewModel = viewModel(
                            key = "home-$selectedDate",
                            factory = HomeViewModel.Factory(
                                repository = salesRepository,
                                date = selectedDate
                            )
                        )
                        val state by counterViewModel.uiState.collectAsStateWithLifecycle()

                        HomeScreen(
                            date = selectedDate,
                            walkIns = state.walkIns,
                            conversions = state.conversions,
                            onDateSelected = { newDate ->
                                selectedDate = newDate
                            },
                            onAddWalkIn = counterViewModel::addWalkIn,
                            onRemoveWalkIn = counterViewModel::removeWalkIn,
                            onResetWalkIns = counterViewModel::resetWalkIns,
                            onRecordSale = {
                                saleSessionId += 1
                                showSaleRecorder = true
                            }
                        )
                    }
                }
            }
        }
    }
}
