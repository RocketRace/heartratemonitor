package com.heartratemonitor.app

import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHrData

// A default implementation of IncomingDataHook that does nothing
object NullIncomingDataHook: IncomingDataHook {
    override fun heartRateHook(identifier: String, data: PolarHrData) {
        // Nothing
    }
    override fun batteryLevelHook(identifier: String, level: Int) {
        // Nothing
    }
    override fun onConnect(info: PolarDeviceInfo) {
        // Still nothing
    }
}