package com.example.inventory.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.inventory.mobile.auth.SessionManager
import com.example.inventory.mobile.ui.LoginScreen
import com.example.inventory.mobile.ui.SignedInApp
import com.example.inventory.mobile.ui.SignUpScreen
import com.example.inventory.mobile.ui.theme.InventoryTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.example.inventory.mobile.ui.ChangePasswordScreen

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var session: SessionManager

    @Inject
    lateinit var outboxCoordinator: com.example.inventory.mobile.offline.OutboxCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            InventoryTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by session.state.collectAsStateWithLifecycle()

                    var showSignUp by rememberSaveable { mutableStateOf(false) }

                    when (state) {
                        is SessionManager.State.LoggedOut ->
                            if (showSignUp) {
                                SignUpScreen(onBackToSignIn = { showSignUp = false })
                            } else {
                                LoginScreen(onCreateAccount = { showSignUp = true })
                            }
                        is SessionManager.State.MustChangePassword -> {
                            showSignUp = false
                            ChangePasswordScreen(onSignOut = { session.signOut() })
                        }

                        is SessionManager.State.LoggedIn -> {
                            showSignUp = false

                            val signedIn = state as SessionManager.State.LoggedIn

                            LaunchedEffect(Unit) { session.refreshCurrentUser() }

                            SignedInApp(
                                role = signedIn.role,
                                tenantName = signedIn.tenantName,
                                timezone = signedIn.timezone,
                                onSignOut = { session.signOut() },
                                outboxCoordinator = outboxCoordinator,
                            )
                        }
                    }
                }
            }
        }
    }
}
