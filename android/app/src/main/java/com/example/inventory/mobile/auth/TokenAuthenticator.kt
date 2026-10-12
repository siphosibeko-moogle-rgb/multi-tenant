package com.example.inventory.mobile.auth

import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi

class AuthInterceptor(private val tokens: TokenStore) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (request.url.encodedPath.contains("/auth/")) {
            return chain.proceed(request)
        }

        val token = tokens.accessToken() ?: return chain.proceed(request)
        return chain.proceed(
            request.newBuilder().header("Authorization", "Bearer $token").build()
        )
    }
}

class TokenAuthenticator(
    private val tokens: TokenStore,
    private val baseUrl: String,
    private val refreshClient: OkHttpClient,
    private val moshi: Moshi,
    private val onSessionExpired: () -> Unit,
) : Authenticator {

    @JsonClass(generateAdapter = false)
    internal data class RefreshRequest(val refreshToken: String)

    @JsonClass(generateAdapter = false)
    internal data class RefreshResponse(val accessToken: String?, val refreshToken: String?)

    private val refreshLock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) {
            expireSession()
            return null
        }

        val failedToken = response.request.header("Authorization")
            ?.removePrefix("Bearer ")
            ?.trim()

        synchronized(refreshLock) {
            val current = tokens.accessToken()

            if (current != null && current != failedToken) {
                return response.request.retryWith(current)
            }

            val refreshToken = tokens.refreshToken() ?: run {
                expireSession()
                return null
            }

            val newAccess = refresh(refreshToken) ?: run {
                expireSession()
                return null
            }

            return response.request.retryWith(newAccess)
        }
    }

    private fun refresh(refreshToken: String): String? {
        val requestAdapter = moshi.adapter(RefreshRequest::class.java)
        val responseAdapter = moshi.adapter(RefreshResponse::class.java)

        val body = requestAdapter.toJson(RefreshRequest(refreshToken))
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/auth/refresh")
            .post(body)
            .build()

        return try {
            refreshClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val parsed = responseAdapter.fromJson(response.body?.string().orEmpty())
                val access = parsed?.accessToken?.takeIf { it.isNotBlank() } ?: return null
                val rotated = parsed.refreshToken?.takeIf { it.isNotBlank() } ?: return null

                tokens.save(access, rotated)
                access
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun expireSession() {
        tokens.clear()
        onSessionExpired()
    }

    private fun Request.retryWith(token: String): Request =
        newBuilder().header("Authorization", "Bearer $token").build()

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
