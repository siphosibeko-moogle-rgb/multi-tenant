package com.example.inventory.mobile.auth

import com.example.inventory.api.apis.AuthenticationApi
import com.example.inventory.api.apis.UsersApi
import com.example.inventory.api.infrastructure.Serializer
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class SessionManagerTest {

    private class FakeTokenStore(
        private var access: String? = null,
        private var refresh: String? = null,
        var failOnClear: Boolean = false,
    ) : TokenStore {
        var cleared = false

        override fun accessToken(): String? = access
        override fun refreshToken(): String? = refresh
        override fun save(accessToken: String, refreshToken: String) {
            access = accessToken
            refresh = refreshToken
        }

        override fun clear() {
            if (failOnClear) {
                throw IllegalStateException("keystore unavailable")
            }
            cleared = true
            access = null
            refresh = null
        }
    }

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun retrofit(): Retrofit = Retrofit.Builder()
        .baseUrl(server.url("/api/v1/"))
        .addConverterFactory(MoshiConverterFactory.create(Serializer.moshi))
        .build()

    private fun api(): AuthenticationApi = retrofit().create(AuthenticationApi::class.java)

    private fun usersApi(): UsersApi = retrofit().create(UsersApi::class.java)

    @Test
    fun `signOut clears the tokens and reports LoggedOut`() {
        val tokens = FakeTokenStore(access = "an-access-token", refresh = "a-refresh-token")
        val session = SessionManager(tokens, api(), usersApi())

        assertTrue(session.state.value is SessionManager.State.LoggedIn)

        session.signOut()

        assertEquals(SessionManager.State.LoggedOut, session.state.value)
        assertTrue("the tokens must be cleared, not merely forgotten", tokens.cleared)
        assertNull(tokens.accessToken())
        assertNull(tokens.refreshToken())
    }

    @Test
    fun `signOut reports LoggedOut even when clearing the tokens fails`() {
        val tokens = FakeTokenStore(access = "an-access-token", failOnClear = true)
        val session = SessionManager(tokens, api(), usersApi())

        session.signOut()

        assertEquals(
            "a failed token wipe must not strand the user on the signed-in screen",
            SessionManager.State.LoggedOut,
            session.state.value,
        )
    }

    @Test
    fun `a failed refresh lands in the same place as an explicit sign-out`() {
        val tokens = FakeTokenStore(access = "an-access-token")
        val session = SessionManager(tokens, api(), usersApi())

        session.onSessionExpired()

        assertEquals(SessionManager.State.LoggedOut, session.state.value)
    }
}
