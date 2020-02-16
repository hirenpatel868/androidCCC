package mustafaozhan.github.com.mycurrencies.ui.main.fragment.calculator

import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.crashlytics.android.Crashlytics
import io.reactivex.Completable
import kotlinx.coroutines.launch
import mustafaozhan.github.com.mycurrencies.base.viewmodel.BaseDataViewModel
import mustafaozhan.github.com.mycurrencies.data.repository.BackendRepository
import mustafaozhan.github.com.mycurrencies.data.repository.PreferencesRepository
import mustafaozhan.github.com.mycurrencies.function.extension.calculateResult
import mustafaozhan.github.com.mycurrencies.function.extension.getFormatted
import mustafaozhan.github.com.mycurrencies.function.extension.getThroughReflection
import mustafaozhan.github.com.mycurrencies.function.extension.insertInitialCurrencies
import mustafaozhan.github.com.mycurrencies.function.extension.removeUnUsedCurrencies
import mustafaozhan.github.com.mycurrencies.function.extension.replaceNonStandardDigits
import mustafaozhan.github.com.mycurrencies.function.extension.replaceUnsupportedCharacters
import mustafaozhan.github.com.mycurrencies.function.extension.toPercent
import mustafaozhan.github.com.mycurrencies.function.scope.either
import mustafaozhan.github.com.mycurrencies.function.scope.mapTo
import mustafaozhan.github.com.mycurrencies.function.scope.whether
import mustafaozhan.github.com.mycurrencies.function.scope.whetherNot
import mustafaozhan.github.com.mycurrencies.model.Currencies
import mustafaozhan.github.com.mycurrencies.model.Currency
import mustafaozhan.github.com.mycurrencies.model.CurrencyResponse
import mustafaozhan.github.com.mycurrencies.model.Rates
import mustafaozhan.github.com.mycurrencies.room.dao.CurrencyDao
import mustafaozhan.github.com.mycurrencies.room.dao.OfflineRatesDao
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.mariuszgromada.math.mxparser.Expression

/**
 * Created by Mustafa Ozhan on 2018-07-12.
 */
@Suppress("TooManyFunctions")
class CalculatorViewModel(
    preferencesRepository: PreferencesRepository,
    private val backendRepository: BackendRepository,
    private val currencyDao: CurrencyDao,
    private val offlineRatesDao: OfflineRatesDao
) : BaseDataViewModel(preferencesRepository) {

    companion object {
        private const val DATE_FORMAT = "HH:mm:ss dd.MM.yyyy"
        private const val MINIMUM_ACTIVE_CURRENCY = 2
        private const val MAXIMUM_INPUT = 15
    }

    val currencyListLiveData: MutableLiveData<MutableList<Currency>> = MutableLiveData()
    val calculatorViewStateLiveData: MutableLiveData<CalculatorViewState> = MutableLiveData()
    val outputLiveData: MutableLiveData<String> = MutableLiveData("0.0")
    var rates: Rates? = null

    override fun onLoaded(): Completable {
        return Completable.complete()
    }

    fun refreshData() {
        calculatorViewStateLiveData.postValue(CalculatorViewState.Loading)
        rates = null
        currencyListLiveData.value?.clear()

        if (mainData.firstRun) {
            currencyDao.insertInitialCurrencies()
            preferencesRepository.updateMainData(firstRun = false)
        }

        currencyListLiveData.postValue(currencyDao.getActiveCurrencies().removeUnUsedCurrencies())
    }

    fun getCurrencies() {
        calculatorViewStateLiveData.postValue(CalculatorViewState.Loading)
        rates?.let { rates ->
            currencyListLiveData.value?.forEach { currency ->
                currency.rate = calculateResultByCurrency(currency.name, rates)
            }
            calculatorViewStateLiveData.postValue(CalculatorViewState.Success(rates))
        } ?: run {
            viewModelScope.launch {
                subscribeService(
                    backendRepository.getAllOnBase(mainData.currentBase),
                    ::rateDownloadSuccess,
                    ::rateDownloadFail
                )
            }
        }
    }

    private fun rateDownloadSuccess(currencyResponse: CurrencyResponse) {
        rates = currencyResponse.rates
        rates?.base = currencyResponse.base
        rates?.date = DateTimeFormat.forPattern(DATE_FORMAT).print(DateTime.now())
        rates?.let {
            calculatorViewStateLiveData.postValue(CalculatorViewState.Success(it))
            offlineRatesDao.insertOfflineRates(it)
        }
    }

    private fun rateDownloadFail(t: Throwable) {
        Crashlytics.logException(t)
        Crashlytics.log(Log.WARN, "rateDownloadFail", t.message)

        calculatorViewStateLiveData.postValue(
            offlineRatesDao.getOfflineRatesOnBase(mainData.currentBase.toString())?.let { offlineRates ->
                CalculatorViewState.OfflineSuccess(offlineRates)
            } ?: run {
                CalculatorViewState.Error
            }
        )
    }

    fun calculateOutput(input: String) {

        Expression(input.replaceUnsupportedCharacters().toPercent())
            .calculate()
            .mapTo { if (isNaN()) "" else getFormatted() }
            ?.whether { length <= MAXIMUM_INPUT }
            ?.let { output ->
                outputLiveData.postValue(output)
                currencyListLiveData.value
                    ?.size
                    ?.whether { it < MINIMUM_ACTIVE_CURRENCY }
                    ?.let { calculatorViewStateLiveData.postValue(CalculatorViewState.FewCurrency) }
                    ?: run { getCurrencies() }
            }
            ?: run { calculatorViewStateLiveData.postValue(CalculatorViewState.MaximumInput(input)) }
    }

    fun updateCurrentBase(currency: String?) {
        rates = null
        setCurrentBase(currency)
        getCurrencies()
    }

    fun loadResetData() = preferencesRepository.loadResetData()

    fun persistResetData(resetData: Boolean) = preferencesRepository.persistResetData(resetData)

    fun getClickedItemRate(name: String): String =
        "1 ${mainData.currentBase.name} = ${rates?.getThroughReflection<Double>(name)}"

    fun getCurrencyByName(name: String) = currencyDao.getCurrencyByName(name)

    fun verifyCurrentBase(spinnerList: List<String>): Currencies {
        mainData.currentBase
            .either(
                { it == Currencies.NULL },
                { spinnerList.indexOf(it.toString()) == -1 }
            )
            ?.let { updateCurrentBase(currencyListLiveData.value?.firstOrNull { it.isActive == 1 }?.name) }

        return mainData.currentBase
    }

    fun calculateResultByCurrency(
        name: String,
        rate: Rates?
    ) = outputLiveData.value
        .toString()
        .whetherNot { isEmpty() }
        ?.let { output ->
            try {
                rate.calculateResult(name, output)
            } catch (e: NumberFormatException) {
                val numericValue = output.replaceUnsupportedCharacters().replaceNonStandardDigits()
                Crashlytics.logException(e)
                Crashlytics.log(Log.ERROR,
                    "NumberFormatException $output to $numericValue",
                    "If no crash making numeric is done successfully"
                )
                rate.calculateResult(name, numericValue)
            }
        } ?: run { 0.0 }

    fun resetFirstRun() {
        preferencesRepository.updateMainData(firstRun = true)
    }
}
