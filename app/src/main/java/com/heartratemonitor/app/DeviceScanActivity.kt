package com.heartratemonitor.app

import android.Manifest
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import com.polar.sdk.api.errors.PolarInvalidArgument
import com.polar.sdk.api.model.PolarDeviceInfo
import io.reactivex.rxjava3.disposables.Disposable


class DeviceScanActivity : HeartRateBaseActivity(), AdapterView.OnItemSelectedListener {
    // UI elements visible in the current activity
    private lateinit var exitButton: Button
    private lateinit var refreshButton: Button
    private lateinit var connectButton: Button
    private lateinit var deviceSpinner: Spinner

    // Results of device scan are added here
    private var knownDevices: MutableList<PolarDeviceInfo> = mutableListOf()
    // The user-selected device data
    private var selectedDeviceId: String? = null
    // User-selected device name
    private var selectedDeviceName: String? = null

    // Keeping track of temporary state while scanning for nearby devices
    private var scanDisposable: Disposable? = null

    private fun updateDeviceSpinner() {
        // Create an ArrayAdapter using our device names and a default spinner layout
        val deviceNames = knownDevices.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, deviceNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        deviceSpinner.adapter = adapter
    }

    private fun applyDeviceScan() {
        debug("Trying to scan for devices...")
        val isDisposed = scanDisposable?.isDisposed ?: true
        if (isDisposed) {
            debug("Fetching info")
            scanDisposable = (application as HeartRateApp).api.searchForDevice()
                .subscribe(
                    {
                        deviceInfoReceived: PolarDeviceInfo ->
                        debug("Polar device found id: " + deviceInfoReceived.deviceId + " address: " + deviceInfoReceived.address + " rssi: " + deviceInfoReceived.rssi + " name: " + deviceInfoReceived.name + " isConnectable: " + deviceInfoReceived.isConnectable)
                        this.knownDevices.add(deviceInfoReceived)
                        // Auto-select the first device in the list
                        if (selectedDeviceId == null) {
                            selectedDeviceId = deviceInfoReceived.deviceId
                            selectedDeviceName = deviceInfoReceived.name
                        }
                        updateDeviceSpinner()
                    },
                    {
                        error: Throwable ->
                        Log.e("HeartRateMonitor", "Device scan failed. Reason $error")
                    },
                    {
                        debug("Scan complete")
                    }
                )
        } else {
            debug("Disposing scan")
            scanDisposable?.dispose()
        }
    }

    override fun onItemSelected(
        parent: AdapterView<*>, view: View?,
        position: Int, id: Long
    ) {
        // Keep track of the currently selected device
        selectedDeviceId = knownDevices[position].deviceId
        selectedDeviceName = knownDevices[position].name
    }

    override fun onNothingSelected(parent: AdapterView<*>) {
        // This is a required method, but we don't do anything
        // since there isn't anything we need to do here
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_scan)

        this.exitButton = findViewById(R.id.exit_button)
        this.exitButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.exit_confirmation))
                .setPositiveButton(R.string.yes) {
                    _: DialogInterface, _: Int ->
                    this.finishAffinity()
                }
                .setNegativeButton(R.string.no, null)
                .show()
        }

        this.connectButton = findViewById(R.id.connect_button)
        this.connectButton.setOnClickListener {
            if (selectedDeviceId == null || selectedDeviceName == null) {
                showToast(R.string.no_devices_available)
            }
            else {
                try {
                    // The device ID cannot be null at this point
                    (application as HeartRateApp).api.connectToDevice(selectedDeviceId!!)
                } catch (polarInvalidArgument: PolarInvalidArgument) {
                    Log.e("HeartRateMonitor", "Failed to connect to device id $selectedDeviceId, due to $polarInvalidArgument ")
                }
                // Construct an intent to switch app activities
                val intent = Intent(this, DataCollectionActivity::class.java).apply {
                    // We share the currently connected device ID & name to the next activity
                    putExtra(sharedDeviceMessage, selectedDeviceId)
                    putExtra(sharedDeviceName, selectedDeviceName)
                }
                startActivity(intent)
            }
        }

        this.refreshButton = findViewById(R.id.refresh_button)
        this.refreshButton.setOnClickListener {
            applyDeviceScan()
        }

        this.deviceSpinner = findViewById(R.id.device_spinner)
        // Our Activity class implements the required methods, so passing the current class is enough
        this.deviceSpinner.onItemSelectedListener = this

        // Request device connection permissions
        // Android Snow Cone (API version 31) added a new BLUETOOTH_SCAN permission which
        // can be used to detect nearby devices. It's the preferred way to detect devices.
        // The fallback is to use location permissions to detect nearby devices.
        val code = 12345
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), code)
                } else {
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), code)
                }
            } else {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), code)
            }
        }
    }

    // Callback is invoked when permissions are given (or not given)
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String?>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        debug("PERMISSIONS RECEIVED")
        if (requestCode == 12345) {
            for (index in 0..grantResults.lastIndex) {
                if (grantResults[index] == PackageManager.PERMISSION_DENIED) {
                    showToast(R.string.missing_permissions)
                    return
                }
            }
            applyDeviceScan()
        }
    }
}