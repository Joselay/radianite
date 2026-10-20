package app.radianite.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.radianite.data.update.AppRelease
import app.radianite.data.update.AppUpdates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [release] is the newer build, if any; [progress] is set while it downloads, 0 until the size is known. */
data class UpdateUiState(
    val release: AppRelease? = null,
    val asking: Boolean = false,
    val progress: Float? = null,
    val message: String? = null,
)

/**
 * Offers a newer build when the app comes to the front: asks once per release, until the user puts it off, then
 * leaves it to the accounts sheet. Updating downloads the APK and hands it to Android's installer.
 */
class UpdateViewModel(
    private val updates: AppUpdates?,
    private val install: (java.io.File) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var lastCheck = 0L
    private var download: Job? = null

    /** Looks for a newer build, at most once an hour. */
    fun check() {
        val updates = updates ?: return
        if (clock() - lastCheck < CHECK_EVERY_MILLIS) return
        lastCheck = clock()
        viewModelScope.launch {
            val release = try {
                updates.newer()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Offline or GitHub unreachable: the next check will do.
                return@launch
            } ?: return@launch
            val ask = updates.shouldAsk(release)
            _state.update { it.copy(release = release, asking = it.asking || ask) }
        }
    }

    fun ask() = _state.update { it.copy(asking = it.release != null, message = null) }

    fun putOff() {
        val release = _state.value.release ?: return
        download?.cancel()
        _state.update { it.copy(asking = false, progress = null, message = null) }
        viewModelScope.launch { updates?.putOff(release) }
    }

    fun update() {
        val release = _state.value.release ?: return
        val updates = updates ?: return
        if (download?.isActive == true) return
        _state.update { it.copy(progress = 0f, message = null) }
        download = viewModelScope.launch {
            try {
                val apk = updates.download(release) { done -> _state.update { it.copy(progress = done) } }
                _state.update { it.copy(progress = null) }
                install(apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(progress = null, message = "Couldn't download the update. Check your connection and try again.") }
            }
        }
    }

    private companion object {
        const val CHECK_EVERY_MILLIS = 60 * 60 * 1000L
    }
}
