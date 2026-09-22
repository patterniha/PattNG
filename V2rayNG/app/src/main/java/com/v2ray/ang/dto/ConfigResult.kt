package com.v2ray.ang.dto

import com.v2ray.ang.dto.entities.ProfileItem

data class ConfigResult(
    var status: Boolean,
    var guid: String? = null,
    var content: String = "",
    var errorMessage: String = "",
    /** True when [errorMessage] is a localized resource string meant for the screen. */
    var localizedError: Boolean = false,
    /**
     * The Aether profile the configuration runs on, when it has an Aether outbound; the daemon
     * starts its core with it. A custom configuration asks for its core with a hand-written
     * command instead, which fills [aetherCommand] and [aetherPort].
     */
    var aetherProfile: ProfileItem? = null,
    /** The command line of the Aether core a custom configuration asks for, without the binary. */
    var aetherCommand: List<String>? = null,
    /** The loopback port [aetherCommand] binds and the configuration's SOCKS outbounds dial. */
    var aetherPort: Int = 0,
)
