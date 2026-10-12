package com.example.inventory.mobile.net

import com.example.inventory.mobile.auth.SessionManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class SessionBindings {

    @Binds
    abstract fun sessionExpiryHandler(sessionManager: SessionManager): SessionExpiryHandler
}
