package com.example.inventory.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.inventory.mobile.net.ApiError
import com.example.inventory.mobile.net.toApiError
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.Response

sealed interface PageResult<out T> {
    data class Ok<T>(val items: List<T>, val nextCursor: String?) : PageResult<T>
    data class Err(val error: ApiError) : PageResult<Nothing>
}

internal inline fun <R, T> Response<R>.toPage(
    items: (R) -> List<T>?,
    nextCursor: (R) -> String?,
): PageResult<T> {
    if (!isSuccessful) return PageResult.Err(toApiError())
    val body = body() ?: return PageResult.Ok(emptyList(), null)
    return PageResult.Ok(items(body).orEmpty(), nextCursor(body))
}

abstract class PagedListViewModel<T> : ViewModel() {

    sealed interface UiState<out T> {
        data object Loading : UiState<Nothing>
        data class Failed(val error: ApiError) : UiState<Nothing>
        data class Loaded<T>(
            val items: List<T>,
            val nextCursor: String?,
            val loadingMore: Boolean = false,
            val moreError: ApiError? = null,
        ) : UiState<T>
    }

    private val _state = MutableStateFlow<UiState<T>>(UiState.Loading)
    val state: StateFlow<UiState<T>> = _state.asStateFlow()

    private var generation = 0
    private var loadedKey: Int? = null

    protected abstract suspend fun fetchPage(cursor: String?): PageResult<T>

    fun loadFor(key: Int) {
        if (key == loadedKey && _state.value !is UiState.Failed) return
        loadedKey = key
        reload()
    }

    fun reload() {
        val mine = ++generation
        _state.value = UiState.Loading
        viewModelScope.launch {
            val result = try {
                fetchPage(null)
            } catch (e: Exception) {
                PageResult.Err(ApiError.fromNetworkFailure(e))
            }
            if (mine != generation) return@launch
            _state.value = when (result) {
                is PageResult.Ok -> UiState.Loaded(result.items, result.nextCursor)
                is PageResult.Err -> UiState.Failed(result.error)
            }
        }
    }

    fun loadMore() {
        val current = _state.value as? UiState.Loaded ?: return
        val cursor = current.nextCursor ?: return
        if (current.loadingMore) return
        val mine = generation
        _state.value = current.copy(loadingMore = true, moreError = null)
        viewModelScope.launch {
            val result = try {
                fetchPage(cursor)
            } catch (e: Exception) {
                PageResult.Err(ApiError.fromNetworkFailure(e))
            }
            if (mine != generation) return@launch
            val latest = _state.value as? UiState.Loaded ?: return@launch
            _state.value = when (result) {
                is PageResult.Ok -> latest.copy(
                    items = latest.items + result.items,
                    nextCursor = result.nextCursor,
                    loadingMore = false,
                )
                is PageResult.Err -> latest.copy(loadingMore = false, moreError = result.error)
            }
        }
    }

    protected fun replaceItem(predicate: (T) -> Boolean, replacement: T?) {
        val current = _state.value as? UiState.Loaded ?: return
        _state.value = current.copy(
            items = if (replacement == null) {
                current.items.filterNot(predicate)
            } else {
                current.items.map { if (predicate(it)) replacement else it }
            },
        )
    }
}

@Composable
fun SubScreenHeader(title: String, onBack: () -> Unit, subtitle: String? = null) {
    BackHandler(onBack = onBack)
    TextButton(onClick = onBack) { Text("← Back") }
    Text(title, style = MaterialTheme.typography.headlineMedium)
    subtitle?.let {
        Spacer(Modifier.height(4.dp))
        Text(it, style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text("Loading…")
    }
}

@Composable
fun ErrorState(error: ApiError, onRetry: () -> Unit) {
    Column {
        Text(
            "Unable to load — check your connection.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(4.dp))
        Text(error.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        error.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
fun EmptyState(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 16.dp))
}

@Composable
fun InlineError(error: ApiError) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(error.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        error.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun LoadMoreFooter(nextCursor: String?, loadingMore: Boolean, error: ApiError?, onLoadMore: () -> Unit) {
    if (nextCursor == null) return
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        error?.let { InlineError(it) }
        OutlinedButton(onClick = onLoadMore, enabled = !loadingMore, modifier = Modifier.fillMaxWidth()) {
            Text(if (loadingMore) "Loading…" else if (error != null) "Try again" else "Load more")
        }
    }
}

@Composable
fun <T> ChoiceRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    enabled: (T) -> Boolean = { true },
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label) },
                enabled = enabled(value),
            )
        }
    }
}

@Composable
fun StatusTag(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

internal fun humanise(wireValue: String): String =
    wireValue.replace('_', ' ').replaceFirstChar { it.uppercase() }

private val dateTimeFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
private val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

internal fun formatDateTime(value: OffsetDateTime, zone: ZoneId? = null): String =
    value.atZoneSameInstant(zone ?: ZoneId.systemDefault()).format(dateTimeFormat)

internal fun formatDate(value: LocalDate): String = value.format(dateFormat)
