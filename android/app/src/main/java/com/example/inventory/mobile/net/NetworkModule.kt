package com.example.inventory.mobile.net

import android.content.Context
import com.example.inventory.api.apis.AuthenticationApi
import com.example.inventory.api.apis.CatalogApi
import com.example.inventory.api.apis.ForecastingApi
import com.example.inventory.api.apis.SalesApi
import com.example.inventory.mobile.BuildConfig
import com.example.inventory.mobile.auth.AuthInterceptor
import com.example.inventory.mobile.auth.EncryptedTokenStore
import com.example.inventory.mobile.auth.TokenAuthenticator
import com.example.inventory.mobile.auth.TokenStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun moshi(): Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Provides
    @Singleton
    fun tokenStore(@ApplicationContext context: Context): TokenStore =
        EncryptedTokenStore(context)

    @Provides
    @Singleton
    @RefreshClient
    fun refreshClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor())
        .build()

    @Provides
    @Singleton
    fun okHttpClient(
        tokens: TokenStore,
        @RefreshClient refreshClient: OkHttpClient,
        moshi: Moshi,
        sessionExpiry: Provider<SessionExpiryHandler>,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(tokens))
        .addInterceptor(loggingInterceptor())
        .authenticator(
            TokenAuthenticator(
                tokens = tokens,
                baseUrl = BuildConfig.BASE_URL,
                refreshClient = refreshClient,
                moshi = moshi,
                onSessionExpired = { sessionExpiry.get().onSessionExpired() },
            )
        )
        .build()

    private fun loggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            redactHeader("Authorization")
        }

    @Provides
    @Singleton
    fun retrofit(client: OkHttpClient, moshi: Moshi): Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.BASE_URL)
        .client(client)
        .addConverterFactory(ScalarsConverterFactory.create())
        .addConverterFactory(MoshiConverterFactory.create(serializerMoshi()))
        .addConverterFactory(QueryEnumConverterFactory)
        .build()

    private fun serializerMoshi(): Moshi = com.example.inventory.api.infrastructure.Serializer.moshi

    @Provides
    @Singleton
    fun authenticationApi(retrofit: Retrofit): AuthenticationApi =
        retrofit.create(AuthenticationApi::class.java)

    @Provides
    @Singleton
    fun catalogApi(retrofit: Retrofit): CatalogApi = retrofit.create(CatalogApi::class.java)

    @Provides
    @Singleton
    fun salesApi(retrofit: Retrofit): SalesApi = retrofit.create(SalesApi::class.java)

    @Provides
    @Singleton
    fun inventoryApi(retrofit: Retrofit): com.example.inventory.api.apis.InventoryApi =
        retrofit.create(com.example.inventory.api.apis.InventoryApi::class.java)

    @Provides
    @Singleton
    fun forecastingApi(retrofit: Retrofit): ForecastingApi =
        retrofit.create(ForecastingApi::class.java)

    @Provides
    @Singleton
    fun usersApi(retrofit: Retrofit): com.example.inventory.api.apis.UsersApi =
        retrofit.create(com.example.inventory.api.apis.UsersApi::class.java)

    @Provides
    @Singleton
    fun purchasingApi(retrofit: Retrofit, client: OkHttpClient): com.example.inventory.api.apis.PurchasingApi =
        retrofit.newBuilder()
            .client(withoutConnectionRetry(client))
            .build()
            .create(com.example.inventory.api.apis.PurchasingApi::class.java)

    internal fun withoutConnectionRetry(client: OkHttpClient): OkHttpClient =
        client.newBuilder().retryOnConnectionFailure(false).build()

    @Provides
    @Singleton
    fun suppliersApi(retrofit: Retrofit): com.example.inventory.api.apis.SuppliersApi =
        retrofit.create(com.example.inventory.api.apis.SuppliersApi::class.java)
}

@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RefreshClient

interface SessionExpiryHandler {
    fun onSessionExpired()
}
