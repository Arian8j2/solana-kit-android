package io.horizontalsystems.solanakit

import android.app.Application
import android.content.Context
import com.metaplex.lib.programs.token_metadata.accounts.MetadataAccountJsonAdapterFactory
import com.metaplex.lib.programs.token_metadata.accounts.MetadataAccountRule
import com.solana.actions.Action
import com.solana.api.Api
import com.solana.networking.Network
import com.solana.networking.NetworkingRouterConfig
import com.solana.networking.OkHttpNetworkingRouter
import io.horizontalsystems.solanakit.core.*
import io.horizontalsystems.solanakit.database.main.MainStorage
import io.horizontalsystems.solanakit.database.transaction.TransactionStorage
import io.horizontalsystems.solanakit.models.Address
import io.horizontalsystems.solanakit.models.FullTransaction
import io.horizontalsystems.solanakit.models.RpcSource
import io.horizontalsystems.solanakit.network.ConnectionManager
import io.horizontalsystems.solanakit.noderpc.ApiSyncer
import io.horizontalsystems.solanakit.noderpc.NftClient
import io.horizontalsystems.solanakit.transactions.SolscanClient
import io.horizontalsystems.solanakit.transactions.TransactionManager
import io.horizontalsystems.solanakit.transactions.TransactionSyncer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.math.BigDecimal
import java.util.*

class SolanaKit(
    private val apiSyncer: ApiSyncer,
    private val balanceManager: BalanceManager,
    private val tokenAccountManager: TokenAccountManager,
    private val transactionManager: TransactionManager,
    private val syncManager: SyncManager,
    rpcSource: RpcSource,
    private val address: Address,
) : ISyncListener {

    private var scope: CoroutineScope? = null

    private val _balanceSyncStateFlow = MutableStateFlow(syncState)
    private val _tokenBalanceSyncStateFlow = MutableStateFlow(tokenBalanceSyncState)
    private val _transactionsSyncStateFlow = MutableStateFlow(transactionsSyncState)

    private val _lastBlockHeightFlow = MutableStateFlow(lastBlockHeight)
    private val _balanceFlow = MutableStateFlow(balance)

    val isMainnet: Boolean = rpcSource.endpoint.network == Network.mainnetBeta
    val receiveAddress = address.publicKey.toBase58()

    val lastBlockHeight: Long?
        get() = apiSyncer.lastBlockHeight
    val lastBlockHeightFlow: StateFlow<Long?> = _lastBlockHeightFlow

    // Balance API
    val syncState: SyncState
        get() = syncManager.balanceSyncState
    val balanceSyncStateFlow: StateFlow<SyncState> = _balanceSyncStateFlow
    val balance: Long?
        get() = balanceManager.balance
    val balanceFlow: StateFlow<Long?> = _balanceFlow

    // Token accounts API
    val tokenBalanceSyncState: SyncState
        get() = syncManager.tokenBalanceSyncState
    val tokenBalanceSyncStateFlow: StateFlow<SyncState> = _tokenBalanceSyncStateFlow
    fun tokenBalance(mintAddress: String): BigDecimal? =
        tokenAccountManager.balance(mintAddress)
    fun tokenBalanceFlow(mintAddress: String): Flow<BigDecimal> = tokenAccountManager.tokenBalanceFlow(mintAddress)

    // Transactions API
    val transactionsSyncState: SyncState
        get() = syncManager.transactionsSyncState
    val transactionsSyncStateFlow: StateFlow<SyncState> = _transactionsSyncStateFlow
    fun allTransactionsFlow(incoming: Boolean?): Flow<List<FullTransaction>> = transactionManager.allTransactionsFlow(incoming)
    fun solTransactionsFlow(incoming: Boolean?): Flow<List<FullTransaction>> = transactionManager.solTransactionsFlow(incoming)
    fun splTransactionsFlow(mintAddress: String, incoming: Boolean?): Flow<List<FullTransaction>> = transactionManager.splTransactionsFlow(mintAddress, incoming)

    fun start() {
        scope = CoroutineScope(Dispatchers.IO)
        scope?.launch {
            syncManager.start(this)
        }
    }

    fun stop() {
        syncManager.stop()
        scope?.cancel()
    }

    fun refresh() {
        if (!scope?.isActive!!) return

        scope?.launch {
            syncManager.refresh(this)
        }
    }

    fun debugInfo(): String {
        val lines = mutableListOf<String>()
        lines.add("ADDRESS: $address")
        return lines.joinToString { "\n" }
    }

    fun statusInfo(): Map<String, Any> {
        val statusInfo = LinkedHashMap<String, Any>()

        return statusInfo
    }

    override fun onUpdateLastBlockHeight(lastBlockHeight: Long) {
        _lastBlockHeightFlow.tryEmit(lastBlockHeight)
    }

    override fun onUpdateBalanceSyncState(syncState: SyncState) {
        _balanceSyncStateFlow.tryEmit(syncState)
    }

    override fun onUpdateBalance(balance: Long) {
        _balanceFlow.tryEmit(balance)
    }

    override fun onUpdateTokenSyncState(syncState: SyncState) {
        _tokenBalanceSyncStateFlow.tryEmit(syncState)
    }

    override fun onUpdateTransactionSyncState(syncState: SyncState) {
        _transactionsSyncStateFlow.tryEmit(syncState)
    }

    suspend fun getAllTransactions(incoming: Boolean? = null, fromHash: String? = null, limit: Int? = null): List<FullTransaction> =
        transactionManager.getAllTransaction(incoming, fromHash, limit)

    suspend fun getSolTransactions(incoming: Boolean? = null, fromHash: String? = null, limit: Int? = null): List<FullTransaction> =
        transactionManager.getSolTransaction(incoming, fromHash, limit)

    suspend fun getSplTransactions(mintAddress: String, incoming: Boolean? = null, fromHash: String? = null, limit: Int? = null): List<FullTransaction> =
        transactionManager.getSplTransaction(mintAddress, incoming, fromHash, limit)

    suspend fun sendSol(toAddress: Address, amount: Long, signer: Signer): FullTransaction =
        transactionManager.sendSol(toAddress, amount, signer.account)

    suspend fun sendSpl(mintAddress: Address, toAddress: Address, amount: BigDecimal, signer: Signer): FullTransaction =
        transactionManager.sendSpl(mintAddress, toAddress, amount, signer.account)

    sealed class SyncState {
        class Synced : SyncState()
        class NotSynced(val error: Throwable) : SyncState()
        class Syncing(val progress: Double? = null) : SyncState()

        override fun toString(): String = when (this) {
            is Syncing -> "Syncing ${progress?.let { "${it * 100}" } ?: ""}"
            is NotSynced -> "NotSynced ${error.javaClass.simpleName} - message: ${error.message}"
            else -> this.javaClass.simpleName
        }

        override fun equals(other: Any?): Boolean {
            if (other !is SyncState)
                return false

            if (other.javaClass != this.javaClass)
                return false

            if (other is Syncing && this is Syncing) {
                return other.progress == this.progress
            }

            return true
        }

        override fun hashCode(): Int {
            if (this is Syncing) {
                return Objects.hashCode(this.progress)
            }
            return Objects.hashCode(this.javaClass.name)
        }
    }

    open class SyncError : Exception() {
        class NotStarted : SyncError()
        class NoNetworkConnection : SyncError()
    }

    companion object {

        val fee = BigDecimal(0.000005)

        fun getInstance(
            application: Application,
            addressString: String,
            rpcSource: RpcSource,
            walletId: String,
            debug: Boolean = false
        ): SolanaKit {
            val httpClient = httpClient(debug)
            val config = NetworkingRouterConfig(listOf(MetadataAccountRule()), listOf(MetadataAccountJsonAdapterFactory()))
            val router = OkHttpNetworkingRouter(rpcSource.endpoint, httpClient, config)
            val connectionManager = ConnectionManager(application)

            val mainDatabase = SolanaDatabaseManager.getMainDatabase(application, walletId)
            val mainStorage = MainStorage(mainDatabase)

            val rpcApiClient = Api(router)
            val nftClient = NftClient(rpcApiClient)
            val rpcAction = Action(rpcApiClient, listOf())
            val apiSyncer = ApiSyncer(rpcApiClient, rpcSource.syncInterval, connectionManager, mainStorage)
            val address = Address(addressString)

            val balanceManager = BalanceManager(address.publicKey, rpcApiClient, mainStorage)
            val tokenAccountManager = TokenAccountManager(rpcApiClient, mainStorage)

            val transactionDatabase = SolanaDatabaseManager.getTransactionDatabase(application, walletId)
            val transactionStorage = TransactionStorage(transactionDatabase, addressString)
            val solscanClient = SolscanClient(httpClient)
            val transactionManager = TransactionManager(address, transactionStorage, rpcAction, tokenAccountManager)
            val transactionSyncer = TransactionSyncer(address.publicKey, rpcApiClient, solscanClient, nftClient, transactionStorage, transactionManager)

            val syncManager = SyncManager(apiSyncer, balanceManager, tokenAccountManager, transactionSyncer, transactionManager)

            val kit = SolanaKit(apiSyncer, balanceManager, tokenAccountManager, transactionManager, syncManager, rpcSource, address)
            syncManager.listener = kit

            return kit
        }

        fun clear(context: Context, walletId: String) {
            SolanaDatabaseManager.clear(context, walletId)
        }

        private fun httpClient(debug: Boolean): OkHttpClient =
            if (debug) {
                val consoleLogger = HttpLoggingInterceptor.Logger { message -> println(message) }

                val logging = HttpLoggingInterceptor(consoleLogger)
                logging.level = HttpLoggingInterceptor.Level.BODY

                OkHttpClient.Builder().addInterceptor(logging).build()
            } else {
                OkHttpClient()
            }

    }

}
