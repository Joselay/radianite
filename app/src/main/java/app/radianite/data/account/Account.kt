package app.radianite.data.account

import app.radianite.data.Failure
import app.radianite.data.LocalStore
import app.radianite.data.Slots
import app.radianite.data.optional
import app.radianite.data.riot.AuthTokens
import app.radianite.data.riot.RiotPlayerData
import app.radianite.data.riot.RiotSession
import app.radianite.data.riot.RiotSignIn
import app.radianite.data.riot.SessionExpiredException
import app.radianite.data.toFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

sealed interface AccountState {
    /** The saved accounts haven't been read yet. */
    data object Unknown : AccountState

    /** [failure] says why, when the user didn't sign out themselves: [Failure.Expired], or why sign-in failed. */
    data class SignedOut(val failure: Failure? = null) : AccountState
    data object CompletingSignIn : AccountState

    /** The account the app shows. [puuid] keys the per-account screens, so one account never sees another's data. */
    data class SignedIn(val puuid: String, val riotId: String) : AccountState
}

/** An account that's signed in on this device, shown or not. [cardId] is its equipped player card, once known. */
@Serializable
data class SavedAccount(val puuid: String, val riotId: String, val cardId: String? = null)

@Serializable
data class AccountIndex(val active: String? = null, val accounts: List<SavedAccount> = emptyList())

/**
 * The sign-in page's cookie store. Its cookies are taken into the account that signs in, and it's
 * emptied before each sign-in so Riot's page asks who's signing in rather than reusing the last account.
 */
interface SignInCookies {
    /** The page's Riot cookies as a Cookie header, leaving the page's store empty. */
    suspend fun take(): String?
    suspend fun clear()
}

/**
 * Who is signed in. Several accounts can be, each with its own Riot session, cookies and data; one at a time
 * is shown. Restores them at start, adds one from Riot's sign-in redirect, switches between them, and signs
 * one out, clearing its local data, whether the user asks or any call finds its session gone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Account(
    private val device: LocalStore,
    private val signIn: RiotSignIn,
    private val web: SignInCookies,
    private val riotFor: (puuid: String) -> RiotPlayerData,
    scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<AccountState>(AccountState.Unknown)
    val state: StateFlow<AccountState> = _state.asStateFlow()

    private val _saved = MutableStateFlow<List<SavedAccount>>(emptyList())

    /** Every account signed in on this device, in the order they signed in. */
    val saved: StateFlow<List<SavedAccount>> = _saved.asStateFlow()

    // Guards the account index: sign-in, switching and sign-out each read and rewrite it.
    private val mutex = Mutex()

    init {
        scope.launch {
            // Any screen can find the shown account's session gone; that returns the app to sign-in.
            launch {
                state.map { (it as? AccountState.SignedIn)?.puuid }
                    .distinctUntilChanged()
                    .flatMapLatest { puuid -> puuid?.let { riotFor(it).expired.map { puuid } } ?: emptyFlow() }
                    .collect { puuid -> mutex.withLock { if (activePuuid() == puuid) end(puuid, Failure.Expired) } }
            }
            // The player card can change in game, so it's read again each time an account is shown.
            launch {
                state.map { (it as? AccountState.SignedIn)?.puuid }
                    .distinctUntilChanged()
                    .filterNotNull()
                    .collectLatest { refreshCard(it) }
            }
            mutex.withLock { restore() }
        }
    }

    /** Waits until the saved accounts have been read. */
    suspend fun ready() {
        state.first { it != AccountState.Unknown }
    }

    /** Empties the sign-in page's cookies, so it signs in a new account rather than the last one. */
    suspend fun prepareSignIn() = web.clear()

    /** Takes the tokens from Riot's sign-in redirect and shows the account they belong to. */
    suspend fun completeSignIn(tokens: AuthTokens) {
        _state.value = AccountState.CompletingSignIn
        try {
            mutex.withLock {
                // The sign-in page just set the Riot cookies; silent renewal depends on them.
                val cookies = web.take()
                val session = signIn.session(tokens)
                riotFor(session.puuid).adopt(session, cookies)
                show(session)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = AccountState.SignedOut(e.toFailure())
        }
    }

    /** Shows another signed-in account. */
    suspend fun switchTo(puuid: String) = mutex.withLock {
        val session = riotFor(puuid).savedSession()
        if (session != null) show(session) else end(puuid, Failure.Expired)
    }

    /** Signs out the shown account and moves to another signed-in one, if there is one. */
    suspend fun signOut() = mutex.withLock {
        activePuuid()?.let { end(it, failure = null) }
    }

    private suspend fun restore() {
        var index = device.read(Slots.accounts) ?: AccountIndex()
        device.migrateSingleAccount()?.let { session ->
            // Older builds kept the one account's Riot cookies in the sign-in page's store.
            riotFor(session.puuid).adopt(session, web.take())
            index = index.with(session)
            device.write(Slots.accounts, index)
        }
        _saved.value = index.accounts
        val session = index.active?.let { riotFor(it).savedSession() }
        if (_state.value == AccountState.Unknown) {
            _state.value = session?.let { AccountState.SignedIn(it.puuid, it.riotId) } ?: AccountState.SignedOut()
        }
    }

    private suspend fun show(session: RiotSession) {
        val index = (device.read(Slots.accounts) ?: AccountIndex()).with(session)
        device.write(Slots.accounts, index)
        _saved.value = index.accounts
        _state.value = AccountState.SignedIn(session.puuid, session.riotId)
    }

    /** Forgets [puuid]'s session, cookies and data. A user's own sign-out moves on to the next account. */
    private suspend fun end(puuid: String, failure: Failure?) {
        riotFor(puuid).signOut()
        device.forAccount(puuid).clear()
        val index = (device.read(Slots.accounts) ?: AccountIndex()).let { old ->
            AccountIndex(active = old.active.takeIf { it != puuid }, accounts = old.accounts.filterNot { it.puuid == puuid })
        }
        device.write(Slots.accounts, index)
        _saved.value = index.accounts

        val wasShown = (_state.value as? AccountState.SignedIn)?.puuid == puuid
        if (!wasShown) return
        val next = if (failure == null) index.accounts.firstNotNullOfOrNull { riotFor(it.puuid).savedSession() } else null
        if (next != null) show(next) else _state.value = AccountState.SignedOut(failure)
    }

    /** Saves [puuid]'s equipped player card, when Riot says and it has changed. */
    private suspend fun refreshCard(puuid: String) {
        // A session gone mid-call is already announced on expired; the card just waits for the next show.
        val loadout = try {
            optional("Player card") { riotFor(puuid).playerLoadout() }
        } catch (e: SessionExpiredException) {
            null
        }
        val cardId = loadout?.identity?.playerCardId?.takeIf { it.isNotBlank() } ?: return
        mutex.withLock {
            val index = device.read(Slots.accounts) ?: return
            if (index.accounts.none { it.puuid == puuid && it.cardId != cardId }) return
            val updated = index.copy(accounts = index.accounts.map { if (it.puuid == puuid) it.copy(cardId = cardId) else it })
            device.write(Slots.accounts, updated)
            _saved.value = updated.accounts
        }
    }

    private fun activePuuid() = (_state.value as? AccountState.SignedIn)?.puuid

    /** [session]'s account added or renamed, keeping its card, and made the shown one. */
    private fun AccountIndex.with(session: RiotSession): AccountIndex {
        val entry = SavedAccount(session.puuid, session.riotId, accounts.find { it.puuid == session.puuid }?.cardId)
        val accounts = if (accounts.any { it.puuid == session.puuid }) accounts.map { if (it.puuid == session.puuid) entry else it } else accounts + entry
        return AccountIndex(active = session.puuid, accounts = accounts)
    }
}
