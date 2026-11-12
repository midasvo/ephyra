package dev.jdtech.jellyfin.settings.presentation.models

import androidx.annotation.DrawableRes
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import dev.jdtech.jellyfin.settings.domain.models.Preference as PreferenceBackend
import dev.jdtech.jellyfin.settings.presentation.enums.DeviceType

data class PreferenceIntInput(
    @param:StringRes override val nameStringResource: Int,
    @param:StringRes override val descriptionStringRes: Int? = null,
    @param:DrawableRes override val iconDrawableId: Int? = null,
    override val enabled: Boolean = true,
    override val dependencies: List<PreferenceBackend<Boolean>> = emptyList(),
    override val supportedDeviceTypes: List<DeviceType> = listOf(DeviceType.PHONE, DeviceType.TV),
    val backendPreference: PreferenceBackend<Int>,
    @param:StringRes val prefixRes: Int? = null,
    @param:StringRes val suffixRes: Int? = null,
    val value: Int = -1,
    /** CE (#400): the number and its word, such as "1 episode"; the dialog keeps [suffixRes]. */
    @param:PluralsRes val suffixPluralsRes: Int? = null,
    /** CE (#400): the text for 0 where 0 turns the setting off or means no limit. */
    @param:StringRes val zeroLabelRes: Int? = null,
) : Preference
