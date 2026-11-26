# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Line numbers for crash traces, with the source file name hidden.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# CE (#1047): the same rules as app/phone. Upstream kept every class under dev.jdtech here, which
# also kept what the TV app cannot run: the download engine and its notifications, the phone's
# playback service, the preview data.
#
# Navigation 3 routes are @Serializable NavKeys and the back stack is saved and restored by
# class name, so the route classes keep theirs. Member names are not read by name: the generated
# serializers and kotlinx.serialization's consumer rules cover the fields.
-keepnames class * implements androidx.navigation3.runtime.NavKey

# Names stay, so crash traces in issues are readable and WorkManager finds a worker it stored by
# class name after an update. R8 may still remove what is unused, inline and merge app code.
-keep,allowshrinking,allowoptimization class dev.jdtech.jellyfin.** { *; }

# These classes are from okhttp and are not used in Android
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn org.conscrypt.*
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE
