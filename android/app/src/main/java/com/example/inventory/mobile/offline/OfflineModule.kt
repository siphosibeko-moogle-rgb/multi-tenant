package com.example.inventory.mobile.offline

import android.content.Context
import com.example.inventory.api.apis.InventoryApi
import com.example.inventory.api.apis.SalesApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object OfflineModule {

    @Provides
    @Singleton
    fun outbox(@ApplicationContext context: Context): Outbox =
        Outbox(File(context.filesDir, "outbox/pending.log"))

    @Provides
    @Singleton
    fun outboxReplayer(
        outbox: Outbox,
        sales: SalesApi,
        inventory: InventoryApi,
    ): OutboxReplayer = OutboxReplayer(outbox, sales, inventory)
}
