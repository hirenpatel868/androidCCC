package mustafaozhan.github.com.mycurrencies.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import mustafaozhan.github.com.mycurrencies.model.Rates

/**
 * Created by Mustafa Ozhan on 2018-07-20.
 */
@Dao
abstract class OfflineRatesDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertOfflineRates(rates: Rates)

    @Query("SELECT * FROM offline_rates WHERE base=:base")
    abstract fun getOfflineRatesOnBase(base: String): Rates?
}
