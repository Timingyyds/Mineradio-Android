# Keep serial names stable for navigation arguments and saved state payloads.
-keepnames @kotlinx.serialization.Serializable class com.mineradio.app.common.entity.**

# Keep serializer accessors and Parcelable creators while still allowing field/method shrinking.
-keepclassmembers class com.mineradio.app.common.entity.** {
    static **$Companion Companion;
    public static final android.os.Parcelable$Creator CREATOR;
}
-keepclassmembers class com.mineradio.app.common.entity.**$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
