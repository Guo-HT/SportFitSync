package com.sportfit.sync.platform

import com.sportfit.sync.domain.Platform
import com.sportfit.sync.platform.coros.CorosGateway
import com.sportfit.sync.platform.demo.DemoGateway
import com.sportfit.sync.platform.garmin.GarminGateway
import com.sportfit.sync.platform.igpsport.IgpsportGateway
import com.sportfit.sync.platform.onelap.OnelapGateway
import com.sportfit.sync.platform.strava.StravaGateway

/**
 * Maps a [Platform] onto the gateway that knows how to talk to it.
 *
 * Keeping this in one place means the sync engine never branches on platform
 * identity, and adding a platform is a two-line change.
 */
object PlatformRegistry {

    fun gateway(platform: Platform): PlatformGateway = when (platform) {
        Platform.GARMIN -> GarminGateway
        Platform.COROS -> CorosGateway
        Platform.STRAVA -> StravaGateway
        Platform.IGPSPORT -> IgpsportGateway
        Platform.ONELAP -> OnelapGateway
        Platform.DEMO -> DemoGateway
        // Not a network platform: `LOCAL_FILES` is only a history label, and
        // nothing may ask the registry for a gateway for it.
        Platform.LOCAL_FILES -> error("本地文件不是可同步的平台")
    }
}
