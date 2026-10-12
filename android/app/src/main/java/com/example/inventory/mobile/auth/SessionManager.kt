package com.example.inventory.mobile.auth

import com.example.inventory.api.apis.AuthenticationApi
import com.example.inventory.api.models.LoginRequest
import com.example.inventory.api.models.TenantRegistrationRequest
import com.example.inventory.mobile.net.ApiError
import com.example.inventory.mobile.net.SessionExpiryHandler
import com.example.inventory.mobile.net.toApiError
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class SessionManager @Inject constructor(
    private val tokens: TokenStore,
    private val authApi: AuthenticationApi,
    private val usersApi: com.example.inventory.api.apis.UsersApi,
) : SessionExpiryHandler {

    sealed interface State {
        data object LoggedOut : State

        data class LoggedIn(
            val displayName: String,
            val tenantName: String,
            val role: String? = null,
            val timezone: String? = null,
        ) : State
    }

    private val _state = MutableStateFlow<State>(
        if (tokens.accessToken() != null) State.LoggedIn("", "") else State.LoggedOut
    )
    val state: StateFlow<State> = _state.asStateFlow()

    suspend fun refreshCurrentUser() {
        if (_state.value !is State.LoggedIn) {
            return
        }
        try {
            val response = usersApi.meGet()
            val user = response.body()
            if (response.isSuccessful && user != null) {
                _state.value = State.LoggedIn(
                    displayName = user.fullName,
                    tenantName = user.tenant.name,
                    role = user.role.value,
                    timezone = user.timezone,
                )
            }
        } catch (e: Exception) {
        }
    }

    suspend fun login(email: String, password: String, tenantSlug: String?): ApiError? {
        return try {
            val response = authApi.authLoginPost(
                LoginRequest(
                    email = email.trim(),
                    password = password,
                    tenantSlug = tenantSlug?.trim()?.takeIf { it.isNotBlank() },
                    deviceLabel = "Android",
                )
            )

            if (!response.isSuccessful) {
                return response.toApiError()
            }

            val body = response.body()
            val access = body?.accessToken
            val refresh = body?.refreshToken
            if (access == null || refresh == null) {
                return ApiError("Signed in, but the server did not return a session token.")
            }

            tokens.save(access, refresh)
            _state.value = State.LoggedIn(
                displayName = body.user.fullName,
                tenantName = body.user.tenant.name,
                role = body.user.role.value,
                timezone = body.user.timezone,
            )
            null
        } catch (e: Exception) {
            ApiError.fromNetworkFailure(e)
        }
    }

    suspend fun register(
        businessName: String,
        slug: String,
        ownerName: String,
        ownerEmail: String,
        ownerPassword: String,
    ): ApiError? {
        return try {
            val response = authApi.authRegisterTenantPost(
                TenantRegistrationRequest(
                    businessName = businessName.trim(),
                    slug = slug.trim().lowercase(),
                    ownerEmail = ownerEmail.trim(),
                    ownerPassword = ownerPassword,
                    ownerName = ownerName.trim(),
                )
            )

            if (!response.isSuccessful) {
                if (response.code() == 409) {
                    return ApiError(
                        "That business name is taken, try another.",
                        "Business web addresses have to be unique across all businesses here.",
                    )
                }
                return response.toApiError()
            }

            val body = response.body()
            val access = body?.accessToken
            val refresh = body?.refreshToken
            if (access == null || refresh == null) {
                return ApiError("Your business was created, but the server did not return a session token.")
            }

            tokens.save(access, refresh)
            _state.value = State.LoggedIn(
                displayName = body.user.fullName,
                tenantName = body.user.tenant.name,
                role = body.user.role.value,
                timezone = body.user.timezone,
            )
            null
        } catch (e: Exception) {
            ApiError.fromNetworkFailure(e)
        }
    }

    override fun onSessionExpired() {
        _state.value = State.LoggedOut

        try {
            tokens.clear()
        } catch (e: Exception) {
        }
    }

    fun signOut() {
        onSessionExpired()
    }
}
