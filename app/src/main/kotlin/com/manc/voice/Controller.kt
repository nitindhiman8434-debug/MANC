package com.manc.voice

import com.manc.voice.audio.EngineConfig
import com.manc.voice.audio.Meters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Shared state between the UI and the foreground audio service. */
object Controller {
    /** AUTO = clean everywhere (all apps + cellular when supported); others are advanced overrides. */
    enum class Mode { AUTO, VOIP, CELLULAR }
    enum class Run { STOPPED, STARTING, RUNNING, ERROR }

    val config = EngineConfig()

    private val _meters = MutableStateFlow(Meters())
    val meters: StateFlow<Meters> = _meters
    fun publishMeters(m: Meters) { _meters.value = m }

    private val _run = MutableStateFlow(Run.STOPPED)
    val run: StateFlow<Run> = _run
    fun setRun(r: Run) { _run.value = r }

    private val _mode = MutableStateFlow(Mode.AUTO)
    val mode: StateFlow<Mode> = _mode
    fun setMode(m: Mode) { _mode.value = m }

    private val _targetPackage = MutableStateFlow<String?>(null)
    val targetPackage: StateFlow<String?> = _targetPackage
    fun setTarget(pkg: String?) { _targetPackage.value = pkg }

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status
    fun setStatus(s: String) { _status.value = s }

    private val _sinks = MutableStateFlow("")
    val sinks: StateFlow<String> = _sinks
    fun setSinks(s: String) { _sinks.value = s }

    @Volatile var autoStart: Boolean = true

    private val _cellularReport = MutableStateFlow<String?>(null)
    val cellularReport: StateFlow<String?> = _cellularReport
    fun setCellularReport(s: String?) { _cellularReport.value = s }

    // enrollment request flags handled by the service loop
    @Volatile var enrollRequested = false
    @Volatile var enrollFinishRequested = false
    @Volatile var enrolledProfile: String? = null
    private val _enrolled = MutableStateFlow(false)
    val enrolled: StateFlow<Boolean> = _enrolled
    fun setEnrolled(b: Boolean) { _enrolled.value = b }
}
