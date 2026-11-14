import org.gradle.jvm.toolchain.JavaLanguageVersion

object Versions {
    const val APP_CODE = 34
    const val APP_NAME = "1.2.0"

    const val COMPILE_SDK = 37
    const val TARGET_SDK = 37
    // CE-owned: the TV app and the library modules go down to Android 9 (API 28) for Fire OS 7
    // sticks; the phone app stays on Android 10.
    const val MIN_SDK = 28
    const val PHONE_MIN_SDK = 29
    const val BUILD_TOOLS = "37.0.0"

    // CE-owned: upstream moved to 17; the fork stays on 21 (local dev + CI use JDK 21).
    val JAVA = JavaLanguageVersion.of(21)
}