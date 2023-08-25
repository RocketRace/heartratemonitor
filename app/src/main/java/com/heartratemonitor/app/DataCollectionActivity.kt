package com.heartratemonitor.app

import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ToggleButton
import androidx.core.net.toUri
import androidx.core.util.Pair
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarExerciseData
import com.polar.sdk.api.model.PolarExerciseEntry
import com.polar.sdk.api.model.PolarHrData
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId


class DataCollectionActivity : HeartRateBaseActivity(), IncomingDataHook {
    // UI elements
    private lateinit var historyScrollView: ScrollView
    private lateinit var deviceHistory: TextView
    private lateinit var deviceData: TextView
    private lateinit var disconnectButton: Button
    private lateinit var saveButton: Button
    private lateinit var clearButton: Button
    private lateinit var monitorToggleButton: ToggleButton
    private lateinit var highlightButton: Button
    // The different highlight events
    private lateinit var highlightGogglesOnButton: Button
    private lateinit var highlightExerciseBeginsButton: Button
    private lateinit var highlightActivityButton: Button
    private lateinit var highlightExerciseEndsButton: Button
    private lateinit var highlightOtherButton: Button
    // Whether highlight buttons are currently visible
    private var highlightVisible = false

    // The currently connected device ID
    private lateinit var deviceId: String

    // the currently connected device name
    private lateinit var deviceName: String

    // The connected device's battery level, in percent
    private var batteryLevel: Int = 0
    // The current heart rate of the device
    // This is not saved to history, and is used
    // only for the data display
    private var cachedHeartRate: Int = 0

    // Note: For data integrity, only mutate this through one thread.
    // It is not necessarily thread safe. For thread safety,
    // wrap these in synchronizedMap.
    // Tuple type: (heart rate, timestamp)
    private var displayableHeartRates: MutableList<HeartRateDisplayEvent> = mutableListOf()
    // The RR intervals used only for the purpose of storing data
    private var rrMsIntervals: MutableList<Int> = mutableListOf()
    // Stores the timestamps of the highlighted moments
    private var highlights: MutableList<HighlightEvent> = mutableListOf()
    // When this enabled, heart rate + timestamp data will be collected
    private var isStreamingData: Boolean = false
    // The timestamp when data collection was started
    private var startedCollection: LocalDateTime? = null

    private val recordingId = "polar_recording_backup"

    // These two methods (batteryLevelHook and heartRateHook)
    // are used to implement the IncomingDataHook interface
    override fun batteryLevelHook(identifier: String, level: Int) {
        batteryLevel = level
        triggerDisplayUpdate()
    }

    override fun heartRateHook(identifier: String, data: PolarHrData) {
        val timestamp = LocalDateTime.now()
        // Always update the cached heart rate value
        this.cachedHeartRate = data.hr
        triggerDisplayUpdate()
        // Only update the heart rate history if we're actually collecting data
        if (isStreamingData) {
            // Store the raw & displayable data
            this.displayableHeartRates.add(HeartRateDisplayEvent(data.hr, timestamp))
            if (this.rrMsIntervals.addAll(data.rrsMs)) {
                debug("Added new data to RR intervals")
            }
            this.appendRrIntervalsToFileStream(data.rrsMs)
            triggerHistoryUpdate()
        }
    }

    // Toggle the highlight button visibilities
    private fun setHighlightVisibility(vis: Boolean) {
        this.highlightVisible = vis
        val visibility = if (vis) View.VISIBLE else View.INVISIBLE
        highlightGogglesOnButton.visibility = visibility
        highlightExerciseBeginsButton.visibility = visibility
        highlightActivityButton.visibility = visibility
        highlightExerciseEndsButton.visibility = visibility
        highlightOtherButton.visibility = visibility
    }

    private fun setHighlight(kind: HighlightKind) {
        debug("Setting highlight: ${getString(kind.id)}")
        val timestamp = LocalDateTime.now()
        // Separate the highlight storage from the highlight display
        val event = HighlightEvent(kind, timestamp, rrMsIntervals.lastOrNull() ?: -1)
        highlights.add(event)
        displayableHeartRates.lastOrNull()?.highlight = kind
        this.appendHighlightToFileStream(event)
        triggerHistoryUpdate()
        setHighlightVisibility(false)
    }

    private fun hasHistory(): Boolean {
        return (startedCollection != null
                && displayableHeartRates.isNotEmpty()
                && rrMsIntervals.isNotEmpty()
                && highlights.isNotEmpty())
    }

    private fun triggerDisplayUpdate() {
        val result = "$deviceName\n$cachedHeartRate ${getString(R.string.bpm)}\n$batteryLevel%"
        deviceData.text = result
    }

    // Update the heart rate history screen
    // This does string generation
    private fun triggerHistoryUpdate() {
        Log.d("HeartRateActivity", "Updating history...")
        deviceHistory.text = displayableHeartRates.takeLast(10).joinToString("\n") {
            val dateString = formatDateTime(it.timestamp)
            val highlight = if (it.highlight != null) {
                getString(it.highlight!!.id)
            } else { "" }
            "${it.bpm}${getString(R.string.bpm)} $dateString $highlight"
        }
        debug("History updated")
    }

    // These are synchronized to prevent data loss from overlapping writes
    @Synchronized
    private fun createToDownloads(name: String): Boolean {
        debug("Creating file: $name")
        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name)
        try {
            contentResolver.openFileDescriptor(file.toUri(), "w")?.use {} ?: run {
                return false
            }
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
            return false
        } catch (e: IOException) {
            e.printStackTrace()
            return false
        }
        debug("Creation successful")
        return true
    }

    // These are synchronized to prevent data loss from overlapping writes
    @Synchronized
    private fun appendToDownloads(name: String, content: String): Boolean {
        debug("Appending to file: $name")
        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name)
        try {
            contentResolver.openFileDescriptor(file.toUri(), "wa")?.use {
                descriptor ->
                FileOutputStream(descriptor.fileDescriptor).use {
                    // Almost everything supports UTF-8
                    it.write(content.toByteArray(Charsets.UTF_8))
                }
            } ?: run {
                return false
            }
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
            return false
        } catch (e: IOException) {
            e.printStackTrace()
            return false
        }
        debug("Write successful")
        return true
    }

    private fun removeFromDownloads(name: String): Boolean {
        debug("Deleting file $name")
        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name)
        try {
            val deleted = file.delete()
            if (!deleted) {
                return false
            }
        } catch (e: IOException) {
            e.printStackTrace()
            return false
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
            return false
        }
        debug("Delete successful")
        return true
    }

    private fun clearStreamingFiles(): Boolean {
        if (!removeFromDownloads(getPath(metadata = false, temp = true))) {
            return false
        }
        return removeFromDownloads(getPath(metadata = true, temp = true))
    }

    private fun createAndWriteToDownloads(name: String, content: String): Boolean {
        if (!createToDownloads(name)) {
            return false
        }
        return appendToDownloads(name, content)
    }

    private fun getPath(metadata: Boolean, temp: Boolean): String {
        val ts = formatDateTime(startedCollection!!, pathSafe = true)
        val ext = if (metadata) ".csv" else ".txt"
        val suffix = if (metadata) "_" + getString(R.string.metadata_lower) else ""
        val tmp = if (temp) "_temp" else ""
        return "$ts$suffix$tmp$ext"
    }

    private fun metadataHeader(): String {
        // At the top of every metadata file
        return arrayOf(
            R.string.packet_timestamp,
            R.string.from_start,
            R.string.rr_interval_ms,
            R.string.highlight
        ).joinToString("\t") {
            getString(it)
        } + "\r\n"
    }

    private fun startFileStreams() {
        debug("Starting file stream")
        createToDownloads(getPath(metadata = false, temp = true))
        createAndWriteToDownloads(getPath(metadata = true, temp = true), metadataHeader())
        debug("Streaming files created")
    }

    private fun startRecoveryRecording() {
        (application as HeartRateApp).api.startRecording(
            deviceId,
            recordingId,
            PolarBleApi.RecordingInterval.INTERVAL_1S,
            PolarBleApi.SampleType.RR
        ).subscribe(
            { debug("Recovery recording started") },
            { e: Throwable -> debug("Recovery recording could not start: $e") }
        )
    }

    private fun haltAndFetchRecoveryRecording() {
        (application as HeartRateApp).api.stopRecording(deviceId).subscribe({
            debug("Stopped recovery recording successfully")
            (application as HeartRateApp).api.listExercises(deviceId).subscribe({
                entry: PolarExerciseEntry ->
                debug("Received one entry metadata with id ${entry.identifier}: $entry")
                if (entry.identifier == recordingId) {
                    (application as HeartRateApp).api.fetchExercise(deviceId, entry).subscribe({
                        data: PolarExerciseData ->
                        val content = data.hrSamples.joinToString("\r\n")
                        val datetime = LocalDateTime.ofInstant(entry.date.toInstant(), ZoneId.systemDefault())
                        val fmtDatetime = formatDateTime(datetime, pathSafe = true)
                        val path = "${fmtDatetime}_recovery.txt"
                        createAndWriteToDownloads(path, content)
                        debug("Successfully recovered data!")
                    },
                    { e: Throwable -> debug("Failed to fetch recovery data, uh oh! $e") })
                }
            },
            { e: Throwable -> debug("Failed to list exercises: $e") },
            { debug("Done listing exercises") })
        },
        { e: Throwable -> debug("Could not stop recording, $e") })
    }

    private fun appendRrIntervalsToFileStream(intervals: Iterable<Int>) {
        debug("Adding RR intervals to file stream")
        appendToDownloads(getPath(metadata = false, temp = true),
            intervals.joinToString("") {
                "$it\r\n" // Keep trailing newlines after each element, rather than just between elements
            }
        )
        debug("Successfully appended")
    }

    private fun appendHighlightToFileStream(highlight: HighlightEvent) {
        debug("Appending highlight to file stream")
        appendToDownloads(
            getPath(metadata = true, temp = true),
            highlight.format(applicationContext, startedCollection!!) + "\r\n"
        )
        debug("Successfully appended highlight")
    }

    private fun saveFinalRrIntervals(): Boolean {
        debug("Saving RR interval file")
        return createAndWriteToDownloads(
            getPath(metadata = false, temp = false),
            rrMsIntervals.joinToString("\r\n")
        )
    }

    private fun saveFinalMetadata(): Boolean {
        debug("Saving metadata file")
        val content = metadataHeader() + highlights.joinToString("\r\n") {
            // the timestamp is not null since file creation is not possible
            // unless data has been collected
            it.format(applicationContext, startedCollection!!)
        }
        return createAndWriteToDownloads(getPath(metadata = true, temp = false), content)
    }

    override fun onConnect(info: PolarDeviceInfo) {
        debug("Hold on! Recovery possibly detected")
        (application as HeartRateApp).api.requestRecordingStatus(deviceId).subscribe({
            pair: Pair<Boolean, String> ->
            debug("Recovery state: $pair")
            if (pair.first && pair.second == recordingId) {
                AlertDialog.Builder(this)
                    .setMessage(getString(R.string.recording_recovery_notice))
                    .setPositiveButton(R.string.yes) {
                            _, _ ->
                        haltAndFetchRecoveryRecording()
                    }
                    .setNegativeButton(R.string.no, null)
                    .show()
            }
        },
        { error: Throwable -> debug("Error fetching recording status $error") })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_collection)

        // Fetch data from previous activity
        // These cannot be null
        deviceId = intent.getStringExtra(sharedDeviceMessage)!!
        deviceName = intent.getStringExtra(sharedDeviceName)!!

        // These will be used to store the data previews for the device info,
        // as well as the heart rate history
        deviceData = findViewById(R.id.device_data)
        historyScrollView = findViewById(R.id.history_scroll_view)
        deviceHistory = findViewById(R.id.device_history)
        // Make sure heart rate history is scrollable
        deviceHistory.movementMethod = ScrollingMovementMethod()

        disconnectButton = findViewById(R.id.disconnect_button)
        disconnectButton.setOnClickListener {
            setHighlightVisibility(false)
            // This should never be null in proper execution
            (application as HeartRateApp).api.disconnectFromDevice(deviceId)
            // Confirm device disconnection
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.disconnect_warning))
                .setPositiveButton(R.string.yes) {
                    _: DialogInterface, _: Int ->
                    // Temp files may already be deleted at this point, but it's no harm trying again
                    if (startedCollection != null) {
                        if (clearStreamingFiles()) {
                            debug("Successfully cleaned up")
                        } else {
                            debug("Cleaning up failed")
                        }
                    }
                    // Return to our original activity
                    val intent = Intent(this, DeviceScanActivity::class.java)
                    startActivity(intent)
                }
                .setNegativeButton(R.string.no, null)
                .show()
        }

        saveButton = findViewById(R.id.save_button)
        saveButton.setOnClickListener {
            debug("Save pressed")
            setHighlightVisibility(false)
            when {
                isStreamingData -> {
                    showToast(R.string.save_notice)
                }
                startedCollection == null -> {
                    showToast(R.string.no_data_notice)
                }
                else -> {
                    if (saveFinalRrIntervals() && saveFinalMetadata()) {
                        showToast(R.string.save_success)
                        if (clearStreamingFiles()) {
                            debug("Successfully cleaned up")
                        } else {
                            debug("Cleaning up failed")
                        }
                        (application as HeartRateApp).api.stopRecording(deviceId).subscribe({
                            debug("Successfully stopped recording")
                            (application as HeartRateApp).api.listExercises(deviceId).subscribe({
                                entry: PolarExerciseEntry ->
                                debug("Received one final entry metadata with id ${entry.identifier}: $entry")
                                if (entry.identifier == recordingId) {
                                    (application as HeartRateApp).api.removeExercise(deviceId, entry).subscribe(
                                        { debug("successfully cleaned up after recovery file") },
                                        { e: Throwable -> debug("Failed to fetch recovery data, uh oh! $e") }
                                    )
                                }
                            },
                            { e: Throwable -> debug("Failed to list exercises: $e") },
                            { debug("Done listing exercises") })
                        },
                        { e -> debug("Failed to stop recording? $e")})
                    }
                }
            }
        }

        // The different kinds of highlights here
        highlightGogglesOnButton = findViewById(R.id.goggles_on_button)
        highlightGogglesOnButton.setOnClickListener {
            setHighlight(HighlightKind.GogglesOn)
        }
        highlightExerciseBeginsButton = findViewById(R.id.exercise_begins_button)
        highlightExerciseBeginsButton.setOnClickListener {
            setHighlight(HighlightKind.ExerciseBegins)
        }
        highlightActivityButton = findViewById(R.id.activity_button)
        highlightActivityButton.setOnClickListener {
            setHighlight(HighlightKind.Activity)
        }
        highlightExerciseEndsButton = findViewById(R.id.exercise_ends_button)
        highlightExerciseEndsButton.setOnClickListener {
            setHighlight(HighlightKind.ExerciseEnds)
        }
        highlightOtherButton = findViewById(R.id.other_button)
        highlightOtherButton.setOnClickListener {
            setHighlight(HighlightKind.Other)
        }
        // Toggle the visibility of the special highlight buttons
        highlightButton = findViewById(R.id.highlight_button)
        highlightButton.setOnClickListener {
            setHighlightVisibility(!highlightVisible)
        }

        clearButton = findViewById(R.id.clear_button)
        clearButton.setOnClickListener {
            setHighlightVisibility(false)
            // Create a confirmation screen for clearing history
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.clear_warning))
                .setPositiveButton(R.string.yes) {
                    _: DialogInterface, _: Int ->
                    // Clear the collected data
                    displayableHeartRates.clear()
                    highlights.clear()
                    // Temp files should be deleted
                    if (startedCollection != null) {
                        if (clearStreamingFiles()) {
                            debug("Successfully cleaned up")
                        } else {
                            debug("Cleaning up failed")
                        }
                    }
                    // This is only reset after clearing (i.e. you can record multiple discontinuous
                    // segments back to back)
                    // Make sure to set this to null after clearing streaming files, not before!
                    startedCollection = null
                    triggerHistoryUpdate()
                }
                .setNegativeButton(R.string.no, null)
                .show()
        }

        monitorToggleButton = findViewById(R.id.monitor_toggle)
        monitorToggleButton.setOnCheckedChangeListener {
            _, buttonIsChecked ->
            setHighlightVisibility(false)
            if (buttonIsChecked && !hasHistory()) {
                // Remember the moment we started collecting data for the first time
                startedCollection = LocalDateTime.now()
                // Initialize the file streams (so you can write data as it comes in)
                startFileStreams()
                // Initialize the recovery on-device RR recording
                startRecoveryRecording()
            }
            // Make sure our data collection state matches the button state
            isStreamingData = buttonIsChecked
        }


        // Initialize this as the data hook
        (application as HeartRateApp).setDataHook(this)
    }
}