-keep class com.example.inventory.api.models.** { *; }
-keep class com.example.inventory.mobile.auth.TokenAuthenticator$RefreshRequest { *; }
-keep class com.example.inventory.mobile.auth.TokenAuthenticator$RefreshResponse { *; }
-keep class kotlin.Metadata { *; }
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,Signature,InnerClasses,EnclosingMethod

-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
