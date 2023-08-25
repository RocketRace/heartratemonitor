package com.heartratemonitor.app

import android.os.Bundle
import android.os.PersistableBundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.util.*

abstract class HeartRateBaseActivity: AppCompatActivity() {
    protected val sharedDeviceMessage = "com.example.example.SHARED_DEVICE_MESSAGE"
    protected val sharedDeviceName = "com.example.example.SHARED_DEVICE_NAME"

    // Background, foreground and cleanup callbacks
    override fun onResume() {
        super.onResume()
        (application as HeartRateApp).api.foregroundEntered()
    }

    override fun onDestroy() {
        super.onDestroy()
        // If a data hook was in place as the activity is getting destroyed,
        // make sure it is unset.
        (application as HeartRateApp).removeDataHook()
        (application as HeartRateApp).api.shutDown()
    }

    // utility functions
    fun showToast(stringResourceId: Int) {
        Toast.makeText(this, getString(stringResourceId), Toast.LENGTH_SHORT).show()
    }
}