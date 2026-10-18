
package com.example.inventory.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope

import com.example.inventory.api.apis.UsersApi
import com.example.inventory.api.models.User
import com.example.inventory.api.models.UserInviteRequest
import com.example.inventory.api.models.UserInviteResponse
import com.example.inventory.api.models.UserRole
import com.example.inventory.api.models.UsersUserIdPatchRequest
import com.example.inventory.mobile.net.ApiError
import com.example.inventory.mobile.net.toApiError

import dagger.hilt.android.lifecycle.HiltViewModel

import java.util.UUID
import javax.inject.Inject

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch


// ============================================================
// USERS VIEW MODEL
// ============================================================

@HiltViewModel
class UsersViewModel @Inject constructor(
    private val users: UsersApi,
) : PagedListViewModel<User>() {

    data class ActionState(
        val busy: UUID? = null,
        val error: ApiError? = null,
        val message: String? = null,
    )

    private val _action = MutableStateFlow(ActionState())
    val action: StateFlow<ActionState> = _action.asStateFlow()

    private val _me = MutableStateFlow<UUID?>(null)
    val me: StateFlow<UUID?> = _me.asStateFlow()

    override suspend fun fetchPage(cursor: String?): PageResult<User> {
        if (cursor == null && _me.value == null) {
            _me.value = runCatching {
                users.meGet().body()?.id
            }.getOrNull()
        }

        return users.usersGet(
            cursor = cursor,
            limit = 50,
        ).toPage(
            { it.items },
            { it.nextCursor },
        )
    }

    fun changeRole(
        user: User,
        role: UserRole,
    ) = patch(
        user,
        UsersUserIdPatchRequest(role = role),
        "${user.fullName} is now ${humanise(role.value).lowercase()}.",
    )

    fun setAccess(
        user: User,
        enabled: Boolean,
    ) = patch(
        user,
        UsersUserIdPatchRequest(
            status = if (enabled) {
                UsersUserIdPatchRequest.Status.ACTIVE
            } else {
                UsersUserIdPatchRequest.Status.DISABLED
            },
        ),
        if (enabled) {
            "${user.fullName} can sign in again."
        } else {
            "${user.fullName} can no longer sign in."
        },
    )

    fun remove(user: User) = act(user) {
        val response = users.usersUserIdDelete(user.id)

        if (response.isSuccessful) {
            replaceItem({ it.id == user.id }, null)

            ActionState(
                message = "${user.fullName} was removed."
            )
        } else {
            ActionState(error = response.toApiError())
        }
    }

    fun clearMessage() {
        _action.value = _action.value.copy(
            message = null,
            error = null,
        )
    }

    private fun patch(
        user: User,
        body: UsersUserIdPatchRequest,
        success: String,
    ) = act(user) {
        val response = users.usersUserIdPatch(user.id, body)
        val updated = response.body()

        if (response.isSuccessful && updated != null) {
            replaceItem({ it.id == user.id }, updated)

            ActionState(message = success)
        } else {
            ActionState(error = response.toApiError())
        }
    }

    private fun act(
        user: User,
        block: suspend () -> ActionState,
    ) {
        if (_action.value.busy != null) return

        _action.value = ActionState(busy = user.id)

        viewModelScope.launch {
            _action.value = try {
                block()
            } catch (e: Exception) {
                ActionState(
                    error = ApiError.fromNetworkFailure(e)
                )
            }
        }
    }
}


// ============================================================
// INVITATION VALIDATION
// ============================================================

internal fun validateInvite(
    fullName: String,
    email: String,
    role: UserRole?,
): String? = when {
    fullName.isBlank() ->
        "Enter the person's name."

    email.isBlank() ->
        "Enter an email address."

    !email.trim().matches(
        Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")
    ) ->
        "Enter a valid email address."

    role == null ->
        "Choose a role."

    else -> null
}


// ============================================================
// INVITE USER VIEW MODEL
// ============================================================

@HiltViewModel
class InviteUserViewModel @Inject constructor(
    private val users: UsersApi,
) : ViewModel() {

    data class UiState(
        val submitting: Boolean = false,
        val validation: String? = null,
        val error: ApiError? = null,
        val invited: UserInviteResponse? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var formToken: String? = null

    fun begin(token: String) {
        if (token == formToken) return

        formToken = token
        _state.value = UiState()
    }

    fun submit(
        fullName: String,
        email: String,
        role: UserRole?,
    ) {
        if (_state.value.submitting) return

        val validation = validateInvite(
            fullName = fullName,
            email = email,
            role = role,
        )

        if (validation != null) {
            _state.value = UiState(
                validation = validation
            )
            return
        }

        _state.value = UiState(submitting = true)

        viewModelScope.launch {
            _state.value = try {
                val response = users.usersPost(
                    UserInviteRequest(
                        email.trim(),
                        fullName.trim(),
                        role!!,
                    )
                )

                val body = response.body()

                if (response.isSuccessful && body != null) {
                    UiState(invited = body)
                } else {
                    UiState(
                        error = response.toApiError()
                    )
                }
            } catch (e: Exception) {
                UiState(
                    error = ApiError.fromNetworkFailure(e)
                )
            }
        }
    }

    fun dismissResult() {
        _state.value = UiState()
    }
}


// ============================================================
// PENDING USER CHANGES
// ============================================================

private sealed interface PendingChange {
    val user: User

    data class Role(
        override val user: User,
    ) : PendingChange

    data class Access(
        override val user: User,
        val enable: Boolean,
    ) : PendingChange

    data class Remove(
        override val user: User,
    ) : PendingChange
}


// ============================================================
// USERS SCREEN
// ============================================================

@Composable
fun UsersScreen(
    role: String?,
    loadKey: Int,
    onBack: () -> Unit,
    viewModel: UsersViewModel = hiltViewModel(),
) {
    var inviting by rememberSaveable {
        mutableStateOf(false)
    }

    val canManage = canManageUsers(role)

    if (inviting) {
        InviteUserScreen(
            onBack = {
                inviting = false
            },
            onInvited = {
                inviting = false
                viewModel.reload()
            },
        )
        return
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val action by viewModel.action.collectAsStateWithLifecycle()
    val me by viewModel.me.collectAsStateWithLifecycle()

    var pending by remember {
        mutableStateOf<PendingChange?>(null)
    }

    LaunchedEffect(loadKey) {
        viewModel.loadFor(loadKey)
    }

    pending?.let { change ->
        ConfirmChangeDialog(
            change = change,
            onConfirm = { newRole ->
                pending = null

                when (change) {
                    is PendingChange.Role ->
                        newRole?.let {
                            viewModel.changeRole(change.user, it)
                        }

                    is PendingChange.Access ->
                        viewModel.setAccess(
                            change.user,
                            change.enable,
                        )

                    is PendingChange.Remove ->
                        viewModel.remove(change.user)
                }
            },
            onDismiss = {
                pending = null
            },
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        item {
            Spacer(Modifier.height(16.dp))

            SubScreenHeader(
                "Users",
                onBack,
                subtitle = "People who can sign in to this business",
            )

            if (canManage) {
                Button(
                    onClick = {
                        inviting = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Invite user")
                }

                Spacer(Modifier.height(8.dp))
            }

            action.error?.let {
                InlineError(it)
            }

            action.message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }

        when (val current = state) {
            is PagedListViewModel.UiState.Loading -> {
                item {
                    LoadingState()
                }
            }

            is PagedListViewModel.UiState.Failed -> {
                item {
                    ErrorState(current.error) {
                        viewModel.reload()
                    }
                }
            }

            is PagedListViewModel.UiState.Loaded -> {
                if (current.items.isEmpty()) {
                    item {
                        EmptyState("No users found.")
                    }
                }

                items(
                    current.items,
                    key = { it.id },
                ) { user ->
                    UserRow(
                        user = user,
                        isMe = user.id == me,
                        canManage = canManage,
                        busy = action.busy == user.id,
                        anyBusy = action.busy != null,
                        onChange = {
                            viewModel.clearMessage()
                            pending = it
                        },
                    )

                    HorizontalDivider()
                }

                item {
                    LoadMoreFooter(
                        current.nextCursor,
                        current.loadingMore,
                        current.moreError,
                    ) {
                        viewModel.loadMore()
                    }

                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}


// ============================================================
// USER ROW
// ============================================================

@OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)
@Composable
private fun UserRow(
    user: User,
    isMe: Boolean,
    canManage: Boolean,
    busy: Boolean,
    anyBusy: Boolean,
    onChange: (PendingChange) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (isMe) {
                    "${user.fullName} (you)"
                } else {
                    user.fullName
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        Text(
            user.email,
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(4.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusTag(humanise(user.role.value))
            StatusTag(humanise(user.status.value))

            Text(
                user.lastLoginAt?.let {
                    "Last signed in ${formatDateTime(it)}"
                } ?: "Never signed in",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (canManage && !isMe) {
            if (busy) {
                Text(
                    "Saving…",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(
                        onClick = {
                            onChange(PendingChange.Role(user))
                        },
                        enabled = !anyBusy,
                    ) {
                        Text("Change role")
                    }

                    when (user.status) {
                        User.Status.ACTIVE -> {
                            TextButton(
                                onClick = {
                                    onChange(
                                        PendingChange.Access(
                                            user = user,
                                            enable = false,
                                        )
                                    )
                                },
                                enabled = !anyBusy,
                            ) {
                                Text("Disable")
                            }
                        }

                        User.Status.DISABLED -> {
                            TextButton(
                                onClick = {
                                    onChange(
                                        PendingChange.Access(
                                            user = user,
                                            enable = true,
                                        )
                                    )
                                },
                                enabled = !anyBusy,
                            ) {
                                Text("Enable")
                            }
                        }

                        User.Status.INVITED -> Unit
                    }

                    TextButton(
                        onClick = {
                            onChange(PendingChange.Remove(user))
                        },
                        enabled = !anyBusy,
                    ) {
                        Text(
                            if (user.status == User.Status.INVITED) {
                                "Cancel invite"
                            } else {
                                "Remove"
                            }
                        )
                    }
                }
            }
        }
    }
}


// ============================================================
// CONFIRM USER CHANGE DIALOG
// ============================================================

@Composable
private fun ConfirmChangeDialog(
    change: PendingChange,
    onConfirm: (UserRole?) -> Unit,
    onDismiss: () -> Unit,
) {
    val user = change.user

    var newRole by remember(change) {
        mutableStateOf(user.role)
    }

    val (title, body, confirm) = when (change) {
        is PendingChange.Role -> Triple(
            "Change role for ${user.fullName}?",
            "Takes effect when their current session next refreshes.",
            "Change role",
        )

        is PendingChange.Access -> {
            if (change.enable) {
                Triple(
                    "Enable ${user.fullName}?",
                    "They will be able to sign in again.",
                    "Enable",
                )
            } else {
                Triple(
                    "Disable ${user.fullName}?",
                    "They will not be able to sign in again, " +
                        "and you can enable them later. An existing " +
                        "session may remain active until the backend " +
                        "invalidates it. Use Remove if you need to " +
                        "revoke access immediately.",
                    "Disable",
                )
            }
        }

        is PendingChange.Remove -> Triple(
            if (user.status == User.Status.INVITED) {
                "Cancel the invitation for ${user.fullName}?"
            } else {
                "Remove ${user.fullName}?"
            },
            "They will lose access and disappear from this list. " +
                "Their past records are kept. This cannot be undone " +
                "from the app.",
            if (user.status == User.Status.INVITED) {
                "Cancel invite"
            } else {
                "Remove"
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(title)
        },
        text = {
            Column {
                if (change is PendingChange.Role) {
                    RolePicker(
                        selected = newRole,
                        onSelect = {
                            newRole = it
                        },
                    )

                    Spacer(Modifier.height(8.dp))
                }

                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(newRole)
                },
                enabled = change !is PendingChange.Role ||
                    newRole != user.role,
            ) {
                Text(confirm)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
            ) {
                Text("Cancel")
            }
        },
    )
}


// ============================================================
// ROLE DESCRIPTION
// ============================================================

private fun roleSummary(role: UserRole): String =
    when (role) {
        UserRole.OWNER ->
            "Everything, including users"

        UserRole.MANAGER ->
            "Catalogue, purchasing, reports, adjustments"

        UserRole.CLERK ->
            "Record sales, receive stock, count"

        UserRole.VIEWER ->
            "Read only"
    }


// ============================================================
// ROLE PICKER
// ============================================================

@Composable
private fun RolePicker(
    selected: UserRole?,
    onSelect: (UserRole) -> Unit,
) {
    Column {
        UserRole.entries.forEach { role ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
            ) {
                RadioButton(
                    selected = role == selected,
                    onClick = {
                        onSelect(role)
                    },
                )

                Column(
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(
                        humanise(role.value),
                        style = MaterialTheme.typography.bodyLarge,
                    )

                    Text(
                        roleSummary(role),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}


// ============================================================
// INVITE USER SCREEN
// ============================================================

@Composable
fun InviteUserScreen(
    onBack: () -> Unit,
    onInvited: () -> Unit,
    viewModel: InviteUserViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var fullName by rememberSaveable {
        mutableStateOf("")
    }

    var email by rememberSaveable {
        mutableStateOf("")
    }

    var role by rememberSaveable {
        mutableStateOf<UserRole?>(UserRole.CLERK)
    }

    var copied by remember {
        mutableStateOf(false)
    }

    val context = LocalContext.current

    val formToken = rememberSaveable {
        UUID.randomUUID().toString()
    }

    LaunchedEffect(formToken) {
        viewModel.begin(formToken)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        SubScreenHeader(
            "Invite user",
            onBack = {
                if (state.invited != null) {
                    viewModel.dismissResult()
                    onInvited()
                } else {
                    onBack()
                }
            },
        )

        // ----------------------------------------------------
        // SUCCESS: DISPLAY THE TEMPORARY PASSWORD
        // ----------------------------------------------------

        state.invited?.let { invited ->
            val name = invited.user.fullName

            Text(
                "$name was added as ${
                    humanise(invited.user.role.value).lowercase()
                }.",
                style = MaterialTheme.typography.titleMedium,
            )

            Spacer(Modifier.height(16.dp))

            Text(
                "Temporary password",
                style = MaterialTheme.typography.labelLarge,
            )

            Spacer(Modifier.height(4.dp))

            SelectionContainer {
                Text(
                    invited.temporaryPassword,
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    val clipboard =
                        context.getSystemService(
                            Context.CLIPBOARD_SERVICE
                        ) as ClipboardManager

                    clipboard.setPrimaryClip(
                        ClipData.newPlainText(
                            "Temporary password",
                            invited.temporaryPassword,
                        )
                    )

                    copied = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (copied) "Copied" else "Copy password"
                )
            }

            Spacer(Modifier.height(12.dp))

            Text(
                "This password is shown after the invitation " +
                    "succeeds. Give it to $name securely. The " +
                    "backend must enforce a password change at " +
                    "first sign-in if that is required.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    viewModel.dismissResult()
                    onInvited()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Done")
            }

            return@Column
        }

        // ----------------------------------------------------
        // INVITATION FORM
        // ----------------------------------------------------

        OutlinedTextField(
            value = fullName,
            onValueChange = {
                fullName = it
            },
            label = {
                Text("Full name")
            },
            singleLine = true,
            enabled = !state.submitting,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = email,
            onValueChange = {
                email = it
            },
            label = {
                Text("Email")
            },
            singleLine = true,
            enabled = !state.submitting,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        Text(
            "Role",
            style = MaterialTheme.typography.titleMedium,
        )

        RolePicker(
            selected = role,
            onSelect = {
                role = it
            },
        )

        Spacer(Modifier.height(12.dp))

        (state.validation ?: state.error?.message)?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )

            state.error?.detail?.let { detail ->
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(8.dp))
        }

        Button(
            onClick = {
                viewModel.submit(
                    fullName = fullName,
                    email = email,
                    role = role,
                )
            },
            enabled = !state.submitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (state.submitting) "Sending..." else "Invite"
            )
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = onBack,
            enabled = !state.submitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Cancel")
        }

        Spacer(Modifier.height(12.dp))

        Text(
            "A successful invitation displays the temporary " +
                "password returned by the server. The backend " +
                "must implement and enforce the actual account " +
                "activation and password-change rules.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
