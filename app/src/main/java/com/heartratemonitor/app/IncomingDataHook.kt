package com.heartratemonitor.app

import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHrData

// Interface for custom handling of data from the device
interface IncomingDataHook {
    // Handler for when a heart rate update event is sent
    fun heartRateHook(identifier: String, data: PolarHrData)
    // Handler for when a battery level update event is sent
    fun batteryLevelHook(identifier: String, level: Int)
    fun onConnect(info: PolarDeviceInfo)
}

