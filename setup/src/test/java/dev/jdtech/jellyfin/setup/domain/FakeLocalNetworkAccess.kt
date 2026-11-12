package dev.jdtech.jellyfin.setup.domain

/** A [LocalNetworkAccess] a test sets by hand. */
class FakeLocalNetworkAccess(var denied: Boolean = false, private val settingName: String? = null) :
    LocalNetworkAccess {
    override fun isDenied(): Boolean = denied

    override suspend fun systemSettingName(): String? = settingName
}
