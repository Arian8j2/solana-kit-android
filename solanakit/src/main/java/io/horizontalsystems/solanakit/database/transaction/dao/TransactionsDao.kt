package io.horizontalsystems.solanakit.database.transaction.dao

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteQuery
import io.horizontalsystems.solanakit.models.FullTransaction
import io.horizontalsystems.solanakit.models.TokenTransfer
import io.horizontalsystems.solanakit.models.Transaction

@Dao
interface TransactionsDao {

    @Query("SELECT * FROM `Transaction` WHERE hash = :transactionHash LIMIT 1")
    fun get(transactionHash: String) : Transaction?

    @Query("SELECT * FROM `Transaction` WHERE NOT pending ORDER BY timestamp DESC LIMIT 1")
    fun lastNonPendingTransaction() : Transaction?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertTransactions(transactions: List<Transaction>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertTokenTransfers(tokenTransfers: List<TokenTransfer>)

    @RawQuery
    suspend fun getTransactions(query: SupportSQLiteQuery): List<FullTransactionWrapper>

    data class FullTransactionWrapper(
        @Embedded
        val transaction: Transaction,

        @Relation(
            entity = TokenTransfer::class,
            parentColumn = "hash",
            entityColumn = "transactionHash"
        )
        val tokenTransfers: List<TokenTransfer>
    ) {

        val fullTransaction: FullTransaction
            get() = FullTransaction(
                transaction,
                tokenTransfers
            )

    }

}
