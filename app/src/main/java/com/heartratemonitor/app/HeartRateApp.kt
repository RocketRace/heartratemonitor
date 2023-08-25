package com.heartratemonitor.app

import android.app.Application
import android.util.Log
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHrData

class HeartRateApp : Application() {
    // We opt in to all the Polar API features. This includes:
    // - Heart rate monitoring
    // - Data streaming
    // - File transfer
    // - Device & battery info
    private val selectedApiFeatures = PolarBleApi.ALL_FEATURES

    // API object
    lateinit var api: PolarBleApi

    // The handler for incoming heart rate & battery data, can be mutated by activities
    private var dataHook: IncomingDataHook = NullIncomingDataHook

    // Set / delete the data hook
    fun setDataHook(hook: IncomingDataHook) {
        this.dataHook = hook
    }
    fun removeDataHook() {
        this.dataHook = NullIncomingDataHook
    }

    override fun onCreate() {
        super.onCreate()
        this.api = PolarBleApiDefaultImpl.defaultImplementation(applicationContext, selectedApiFeatures)
        debug("API object created")

        api.setApiCallback(object : PolarBleApiCallback() {
            override fun blePowerStateChanged(powered: Boolean) {
                debug("BLE power: $powered")
            }

            override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                debug("CONNECTED: " + polarDeviceInfo.deviceId)
                dataHook.onConnect(polarDeviceInfo)
            }

            override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
                debug("CONNECTING: " + polarDeviceInfo.deviceId)
            }

            override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
                debug("DISCONNECTED: " + polarDeviceInfo.deviceId)
            }

            override fun streamingFeaturesReady(
                identifier: String,
                features: Set<PolarBleApi.DeviceStreamingFeature>
            ) {
                for (feature in features) {
                    debug("Streaming feature $feature is ready")
                }
            }

            override fun hrFeatureReady(identifier: String) {
                debug("HR READY: $identifier")
            }

            override fun batteryLevelReceived(identifier: String, level: Int) {
                debug("Battery level: $level")
                dataHook.batteryLevelHook(identifier, level)
            }

            override fun hrNotificationReceived(
                identifier: String,
                data: PolarHrData
            ) {
                debug("HR: ${data.hr}")
                dataHook.heartRateHook(identifier, data)
            }

            override fun polarFtpFeatureReady(s: String) {
                // required for the class
            }
        })
    }
}