package com.deepar.flutter_deepar

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

import ai.deepar.ar.ARErrorType
import ai.deepar.ar.AREventListener
import ai.deepar.ar.DeepAR
import ai.deepar.ar.DeepARImageFormat
import ai.deepar.ar.DeepARPixelFormat

/**
 * FlutterDeeparPlugin — Flutter plugin that wraps the DeepAR augmented reality SDK.
 *
 * Provides camera capture via Camera2, AR effect loading, and processed frame
 * output via EventChannel. Frames are delivered as RGBA byte arrays suitable
 * for direct consumption or forwarding to a live-streaming SDK.
 */
class FlutterDeeparPlugin : FlutterPlugin, MethodChannel.MethodCallHandler,
    EventChannel.StreamHandler, ActivityAware {

    companion object {
        private const val TAG = "FlutterDeepAR"
        private const val METHOD_CHANNEL = "flutter_deepar"
        private const val FRAME_CHANNEL = "flutter_deepar/frames"
    }

    // Flutter bindings
    private var methodChannel: MethodChannel? = null
    private var eventChannel: EventChannel? = null
    @Volatile private var frameEventSink: EventChannel.EventSink? = null
    private var applicationContext: Context? = null
    private var activity: Activity? = null

    // DeepAR engine
    private var deepAR: DeepAR? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    // These flags are written/read across the main, camera, and DeepAR render
    // threads. They MUST be @Volatile — without it the ART AOT optimizer in
    // release builds can cache the value in a worker thread and never observe
    // updates (e.g. the DeepAR render thread never sees isCapturing flip),
    // which manifests as a blank preview that only reproduces in release.
    @Volatile private var isSdkInitialized = false
    @Volatile private var isCapturing = false
    private var nativeFrameCount = 0
    private var isFrontCamera = true

    // The rotation/mirror pair passed to receiveFrame is LOCKED to the values
    // of the first camera session of this DeepAR instance's lifetime and never
    // changed again. DeepAR's offscreen pipeline keys itself to the input
    // orientation it first sees; flipping the rotation argument 270<->90 on a
    // live instance (the old switchCamera behaviour) makes frameAvailable stop
    // firing permanently — the switched-to camera froze on its last frame on
    // every Android device. Frames from the lens the session did NOT start on
    // are instead mirrored in the NV21 buffer itself (see flipNV21Horizontal),
    // which composes with the locked rotation+mirror to reproduce exactly the
    // per-lens output the engine would have produced with per-lens parameters.
    // -1 = not yet latched. Reset only when the engine is destroyed.
    @Volatile private var lockedRotation = -1
    @Volatile private var lockedMirror = false

    // Dedicated background thread for camera capture + frame conversion.
    // All Camera2 callbacks and the heavy YUV->NV21 conversion run here so the
    // main/UI thread stays free to composite the host's preview surface. Running
    // this work on the main thread saturates it in release builds (where Dart
    // pushes frames faster) and the preview never gets drawn.
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    // Backpressure gates — at most ONE frame in flight per stage. When the
    // main thread can't keep up (it runs both DeepAR ingest and EventChannel
    // serialization), newer frames are DROPPED instead of queued. Without
    // this, main-thread posts piled up without bound under load: latency
    // snowballed and the heap ballooned. iOS never had the problem because
    // AVFoundation drops late frames (alwaysDiscardsLateVideoFrames).
    private val ingestInFlight = AtomicBoolean(false)
    private val sendInFlight = AtomicBoolean(false)

    // Reused frame buffers, sized on first frame. Allocating fresh multi-MB
    // arrays per frame (~200MB/s at 30fps) kept ART's GC constantly running.
    // Reuse is safe because the in-flight gates above guarantee a buffer is
    // never refilled before its consumer is done with it. Two rotating direct
    // buffers for DeepAR ingest mirror DeepAR's own Android sample.
    private var nv21Bytes: ByteArray? = null
    private var ingestBuffers: Array<ByteBuffer>? = null
    private var ingestBufferIndex = 0
    private var rgbaBytes: ByteArray? = null
    private var uvRowV: ByteArray? = null
    private var uvRowU: ByteArray? = null


    // Camera2 API. @Volatile: written from the main thread (open/close paths)
    // and read from the camera HandlerThread (session callbacks).
    @Volatile private var cameraDevice: CameraDevice? = null
    @Volatile private var captureSession: CameraCaptureSession? = null
    @Volatile private var imageReader: ImageReader? = null
    private var cameraManager: CameraManager? = null

    // Monotonic id for each openCamera() call. Camera2 callbacks capture the
    // generation they were created under and are ignored if a newer open (or
    // closeCamera) has since bumped it — a late onDisconnected/onError from an
    // evicted OLD device must not clobber the NEW device's fields.
    @Volatile private var cameraGeneration = 0

    // Result of the startCapture/switchCamera call currently waiting for its
    // camera session to actually configure. Completed (main thread only) from
    // onConfigured / any failure path / a safety timeout, so the Dart side
    // learns whether the camera really started instead of being told success
    // before the async open even ran.
    private var pendingCameraResult: MethodChannel.Result? = null

    // Camera sensor captures in landscape orientation (1280x720).
    // DeepAR rotates it 270° to portrait.
    private val cameraWidth = 1280
    private val cameraHeight = 720

    // DeepAR offscreen output must be portrait (width < height) to match the
    // rotated frame orientation consumers expect. Configurable from Dart via
    // initializeDeepAR so apps can match their encoder resolution and shrink
    // per-frame transfer cost; defaults preserve pre-0.1.6 behaviour.
    private var outputWidth = 720
    private var outputHeight = 1280

    // ──────────────────────────────────────────────────────────────────────
    //  FlutterPlugin lifecycle
    // ──────────────────────────────────────────────────────────────────────

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        applicationContext = binding.applicationContext
        cameraManager = binding.applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        methodChannel = MethodChannel(binding.binaryMessenger, METHOD_CHANNEL)
        methodChannel?.setMethodCallHandler(this)

        eventChannel = EventChannel(binding.binaryMessenger, FRAME_CHANNEL)
        eventChannel?.setStreamHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel?.setMethodCallHandler(null)
        methodChannel = null
        eventChannel?.setStreamHandler(null)
        eventChannel = null
        cleanup()
    }

    // ──────────────────────────────────────────────────────────────────────
    //  ActivityAware — needed for camera permissions
    // ──────────────────────────────────────────────────────────────────────

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    // ──────────────────────────────────────────────────────────────────────
    //  EventChannel.StreamHandler
    // ──────────────────────────────────────────────────────────────────────

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        Log.d(TAG, "Frame EventChannel: onListen — sink connected")
        frameEventSink = events
    }

    override fun onCancel(arguments: Any?) {
        Log.d(TAG, "Frame EventChannel: onCancel — sink disconnected")
        frameEventSink = null
    }

    // ──────────────────────────────────────────────────────────────────────
    //  MethodChannel dispatch
    // ──────────────────────────────────────────────────────────────────────

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "initializeDeepAR" -> {
                val licenseKey = call.argument<String>("licenseKey")
                if (licenseKey == null) {
                    result.error("INVALID_ARG", "License key is required", null)
                    return
                }
                outputWidth = call.argument<Int>("outputWidth") ?: 720
                outputHeight = call.argument<Int>("outputHeight") ?: 1280
                initializeDeepAR(licenseKey, result)
            }
            "startCapture" -> startCapture(call.argument<Boolean>("front"), result)
            "stopCapture" -> stopCapture(result)
            "loadEffect" -> {
                val effectPath = call.argument<String>("effectPath") ?: ""
                loadEffect(effectPath, result)
            }
            "switchCamera" -> switchCamera(result)
            "destroyDeepAR" -> destroyDeepAR(result)
            else -> result.notImplemented()
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Initialize DeepAR SDK
    //  NOTE: DeepAR's initialize() is asynchronous — the initialized()
    //  callback fires when the engine is truly ready.  We hold the
    //  MethodChannel.Result until that callback so the Dart side doesn't
    //  call startCapture on an engine that hasn't finished setup.
    //  In release builds (AOT) Dart runs fast enough to hit this race.
    // ──────────────────────────────────────────────────────────────────────

    // Pending init result — held until DeepAR's initialized() fires
    private var pendingInitResult: MethodChannel.Result? = null

    private fun initializeDeepAR(licenseKey: String, result: MethodChannel.Result) {
        val ctx = applicationContext ?: run {
            result.error("NO_CONTEXT", "Application context is null", null)
            return
        }

        try {
            Log.d(TAG, "Initializing DeepAR SDK...")

            // Hold the result — we'll complete it in initialized()
            pendingInitResult = result

            deepAR = DeepAR(ctx)
            deepAR?.setLicenseKey(licenseKey)
            deepAR?.initialize(ctx, object : AREventListener {
                override fun screenshotTaken(bitmap: android.graphics.Bitmap?) {}
                override fun videoRecordingStarted() {}
                override fun videoRecordingFinished() {}
                override fun videoRecordingFailed() {}
                override fun videoRecordingPrepared() {}
                override fun shutdownFinished() {
                    Log.d(TAG, "DeepAR shutdown finished")
                }
                override fun initialized() {
                    Log.d(TAG, "DeepAR SDK initialized callback — engine is ready")
                    isSdkInitialized = true
                    // Complete the pending init result now that the engine is truly ready
                    mainHandler.post {
                        pendingInitResult?.success(true)
                        pendingInitResult = null
                    }
                }
                override fun faceVisibilityChanged(visible: Boolean) {}
                override fun imageVisibilityChanged(gameObjectName: String?, visible: Boolean) {}
                override fun frameAvailable(image: Image?) {
                    if (!isCapturing || image == null) return
                    // Do NOT close the image: the SDK acquires it in a
                    // try-with-resources and closes it itself right after this
                    // callback returns (verified in 5.6.4 bytecode). Closing it
                    // here too is a double-close.
                    sendFrameToFlutter(image)
                }
                override fun error(errorType: ARErrorType?, message: String?) {
                    Log.e(TAG, "DeepAR error: $errorType — $message")
                }
                override fun effectSwitched(slot: String?) {
                    Log.d(TAG, "DeepAR effect switched: $slot")
                }
            })

            // Enable off-screen rendering so frameAvailable callback fires.
            // Output is portrait since the camera frame is rotated 270°.
            deepAR?.setOffscreenRendering(outputWidth, outputHeight, DeepARPixelFormat.RGBA_8888)

            nativeFrameCount = 0
            Log.d(TAG, "DeepAR init dispatched (offscreen ${outputWidth}x${outputHeight}), waiting for initialized() callback...")

            // Safety timeout: if initialized() never fires within 5s, resolve anyway
            // so the Dart side isn't stuck waiting forever.
            mainHandler.postDelayed({
                if (pendingInitResult != null) {
                    Log.w(TAG, "Init timeout — resolving pending result")
                    isSdkInitialized = true
                    pendingInitResult?.success(true)
                    pendingInitResult = null
                }
            }, 5000)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize DeepAR: ${e.message}", e)
            pendingInitResult = null
            result.error("INIT_FAILED", e.message, e.stackTraceToString())
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Start camera capture + DeepAR rendering
    // ──────────────────────────────────────────────────────────────────────

    private fun startCapture(front: Boolean?, result: MethodChannel.Result) {
        // Explicit facing request (e.g. "every livestream starts on the
        // front camera"). null = keep the current facing, so callers like
        // app-lifecycle resume don't clobber the user's mid-stream choice.
        if (front != null) isFrontCamera = front
        Log.d(TAG, "startCapture: initialized=$isSdkInitialized front=$isFrontCamera (requested=$front)")
        isCapturing = true
        nativeFrameCount = 0
        setPendingCameraResult(result)
        try {
            startCameraThread()
            openCamera(isFrontCamera)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start capture: ${e.message}", e)
            failPendingCameraResult("START_FAILED", e.message)
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Pending camera result plumbing — all completions hop to the main
    //  thread, where pendingCameraResult is exclusively touched, so a result
    //  can never be completed twice.
    // ──────────────────────────────────────────────────────────────────────

    private fun setPendingCameraResult(result: MethodChannel.Result) {
        // A previous pending call that never resolved (shouldn't happen, but
        // never strand a Dart future) is failed before being replaced.
        pendingCameraResult?.let {
            Log.w(TAG, "Replacing unresolved camera result")
            it.error("SUPERSEDED", "A newer camera call replaced this one", null)
        }
        pendingCameraResult = result
        // Safety net: if the HAL never delivers any terminal callback, resolve
        // with success anyway after 6s (mirrors the init timeout) so Dart
        // can't hang forever. Identity check — only fires for THIS result.
        mainHandler.postDelayed({
            if (pendingCameraResult === result) {
                Log.w(TAG, "Camera result timeout — resolving optimistically")
                pendingCameraResult = null
                result.success(isFrontCamera)
            }
        }, 6000)
    }

    /**
     * Complete the pending result successfully, committing [front] as the new
     * facing — but ONLY if [generation] is still the current camera session.
     * The generation is re-checked on the main thread (the only thread that
     * mutates it), which closes the race where a stale session's callback,
     * already past its camera-thread generation check, would otherwise
     * resolve a NEWER call's result. Facing is committed here, on success,
     * so a failed switch can't desync isFrontCamera from the real camera.
     */
    private fun completePendingCameraResult(generation: Int, front: Boolean) {
        mainHandler.post {
            if (generation != cameraGeneration) return@post
            isFrontCamera = front
            pendingCameraResult?.success(front)
            pendingCameraResult = null
        }
    }

    /** Generation-guarded failure — for camera-thread callback paths. */
    private fun failPendingCameraResult(generation: Int, code: String, message: String?) {
        mainHandler.post {
            if (generation != cameraGeneration) return@post
            pendingCameraResult?.error(code, message, null)
            pendingCameraResult = null
        }
    }

    /** Unconditional failure. MAIN THREAD callers only (method-channel
     *  handlers and mainHandler lambdas) — completes the current pending
     *  result directly, no post, no generation check. */
    private fun failPendingCameraResult(code: String, message: String?) {
        val result = pendingCameraResult
        pendingCameraResult = null
        result?.error(code, message, null)
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Stop camera capture
    // ──────────────────────────────────────────────────────────────────────

    private fun stopCapture(result: MethodChannel.Result) {
        try {
            Log.d(TAG, "Stopping capture...")
            isCapturing = false
            // A start/switch still waiting on its session must not dangle.
            failPendingCameraResult("STOPPED", "Capture stopped before camera session configured")
            closeCamera()
            stopCameraThread()
            Log.d(TAG, "Capture stopped")
            result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop capture: ${e.message}", e)
            result.error("STOP_FAILED", e.message, null)
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Load / unload a DeepAR effect (.deepar file)
    // ──────────────────────────────────────────────────────────────────────

    private fun loadEffect(effectPath: String, result: MethodChannel.Result) {
        try {
            if (effectPath.isEmpty() || effectPath == "None") {
                Log.d(TAG, "Clearing effect")
                deepAR?.switchEffect("effect", null as String?)
            } else if (effectPath.startsWith("/")) {
                // Absolute filesystem paths (effects downloaded at runtime).
                // The String overload resolves paths in NATIVE code and
                // silently no-ops on anything but android_asset URIs — the
                // InputStream overload instead reads the bytes here in Java
                // (synchronously, verified against the SDK bytecode:
                // stream -> byte[] -> switchEffectRawNative), so a bad file
                // throws visibly and a good one always applies.
                Log.d(TAG, "Loading effect from file: $effectPath")
                java.io.FileInputStream(effectPath).use { stream ->
                    deepAR?.switchEffect("effect", stream)
                }
            } else {
                Log.d(TAG, "Loading effect: $effectPath")
                deepAR?.switchEffect("effect", "file:///android_asset/$effectPath")
            }
            result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load effect: ${e.message}", e)
            result.success(true) // Non-fatal
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Switch front / back camera
    // ──────────────────────────────────────────────────────────────────────

    private fun switchCamera(result: MethodChannel.Result) {
        // Target facing is NOT committed to isFrontCamera here — it is
        // committed in completePendingCameraResult once the new session
        // actually configures, so a failed switch leaves the plugin's facing
        // state matching the camera that is really still running.
        val target = !isFrontCamera
        Log.d(TAG, "Switching camera to ${if (target) "FRONT" else "BACK"}")
        setPendingCameraResult(result)
        try {
            closeCamera()
            openCamera(target)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to switch camera: ${e.message}", e)
            failPendingCameraResult("SWITCH_FAILED", e.message)
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Destroy DeepAR pipeline and release resources
    // ──────────────────────────────────────────────────────────────────────

    private fun destroyDeepAR(result: MethodChannel.Result) {
        try {
            Log.d(TAG, "Destroying DeepAR pipeline...")
            cleanup()
            Log.d(TAG, "DeepAR pipeline destroyed")
            result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to destroy DeepAR: ${e.message}", e)
            result.error("DESTROY_FAILED", e.message, null)
        }
    }

    private fun cleanup() {
        isCapturing = false
        failPendingCameraResult("DESTROYED", "DeepAR pipeline destroyed")
        closeCamera()
        stopCameraThread()
        try {
            deepAR?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing DeepAR: ${e.message}")
        }
        deepAR = null
        isSdkInitialized = false
        nativeFrameCount = 0
        frameEventSink = null
        pendingInitResult = null
        // The engine is gone — the next instance latches fresh values, and a
        // fresh instance means a fresh default facing too.
        lockedRotation = -1
        lockedMirror = false
        isFrontCamera = true
        ingestInFlight.set(false)
        sendInFlight.set(false)
        nv21Bytes = null
        ingestBuffers = null
        rgbaBytes = null
        uvRowV = null
        uvRowU = null
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Camera2 API — open camera, feed YUV frames to DeepAR
    // ──────────────────────────────────────────────────────────────────────

    private fun startCameraThread() {
        if (cameraThread != null) return
        cameraThread = HandlerThread("DeepARCamera").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
        Log.d(TAG, "Camera background thread started")
    }

    private fun stopCameraThread() {
        cameraThread?.quitSafely()
        try {
            cameraThread?.join(500)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while stopping camera thread: ${e.message}")
        }
        cameraThread = null
        cameraHandler = null
    }

    private fun openCamera(front: Boolean, isRetry: Boolean = false) {
        val manager = cameraManager ?: run {
            failPendingCameraResult("NO_MANAGER", "CameraManager unavailable")
            return
        }
        // Ensure the background thread exists (e.g. if openCamera is reached
        // via switchCamera without a fresh startCapture).
        if (cameraHandler == null) startCameraThread()
        val camHandler = cameraHandler ?: run {
            failPendingCameraResult("NO_THREAD", "Camera thread unavailable")
            return
        }
        val cameraId = getCameraId(front) ?: run {
            failPendingCameraResult("NO_CAMERA", "No ${if (front) "front" else "back"} camera on this device")
            return
        }
        val ctx = activity ?: applicationContext ?: run {
            failPendingCameraResult("NO_CONTEXT", "No context available")
            return
        }

        if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Camera permission not granted")
            failPendingCameraResult("NO_PERMISSION", "Camera permission not granted")
            return
        }

        // This open supersedes every outstanding camera callback.
        val myGeneration = ++cameraGeneration

        // Latch the engine-lifetime rotation/mirror on the first session (see
        // the lockedRotation field docs for why they can never change again).
        if (lockedRotation == -1) {
            lockedRotation = if (front) 270 else 90
            lockedMirror = front
            Log.d(TAG, "DeepAR input params latched: rotation=$lockedRotation mirror=$lockedMirror")
        }
        // Frames from the lens the engine was NOT latched to are mirrored in
        // the buffer instead; composed with the locked rotation+mirror this
        // yields exactly the upright/mirroring output each lens always had.
        val flipInBuffer = (if (front) 270 else 90) != lockedRotation
        Log.d(TAG, "openCamera gen=$myGeneration front=$front flipInBuffer=$flipInBuffer retry=$isRetry")

        // Close any reader left from a previous open that never went through
        // closeCamera (e.g. a failed open being retried) — otherwise its
        // native buffers leak until the GC finalizer runs.
        try {
            imageReader?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing stale image reader: ${e.message}")
        }

        // Per-session chroma-layout check result. Closure-local so a stale
        // in-flight frame from the PREVIOUS session (still finishing on the
        // camera thread) can never pre-populate the NEW session's check.
        // Only touched on the camera thread — no synchronization needed.
        var uvAliasedForSession: Boolean? = null

        // ImageReader with 4 buffers to prevent "Unable to acquire buffer" warnings
        imageReader = ImageReader.newInstance(
            cameraWidth, cameraHeight,
            ImageFormat.YUV_420_888, 4
        )
        imageReader?.setOnImageAvailableListener({ reader ->
            // acquireLatestImage can throw if the reader was closed on the main
            // thread (closeCamera) while this background callback is in flight.
            val image = try {
                reader.acquireLatestImage()
            } catch (e: IllegalStateException) {
                return@setOnImageAvailableListener
            } ?: return@setOnImageAvailableListener
            try {
                if (!isCapturing) return@setOnImageAvailableListener

                // Drop the frame BEFORE paying for conversion if the main
                // thread hasn't consumed the previous one yet.
                if (!ingestInFlight.compareAndSet(false, true)) {
                    return@setOnImageAvailableListener
                }

                var posted = false
                try {
                    // Convert YUV_420_888 to tightly packed NV21 to guarantee
                    // correct chroma interleaving across all Android devices.
                    val width = image.width
                    val height = image.height
                    val nv21Size = width * height * 3 / 2

                    var nv21 = nv21Bytes
                    if (nv21 == null || nv21.size != nv21Size) {
                        nv21 = ByteArray(nv21Size)
                        nv21Bytes = nv21
                    }

                    val yPlane = image.planes[0]
                    val uPlane = image.planes[1]
                    val vPlane = image.planes[2]

                    val yBuffer = yPlane.buffer
                    val uBuffer = uPlane.buffer
                    val vBuffer = vPlane.buffer

                    val yRowStride = yPlane.rowStride
                    val uvRowStride = uPlane.rowStride
                    val uvPixelStride = uPlane.pixelStride

                    // Copy Y plane
                    var pos = 0
                    if (yRowStride == width) {
                        yBuffer.position(0)
                        yBuffer.get(nv21, 0, width * height)
                        pos += width * height
                    } else {
                        for (row in 0 until height) {
                            yBuffer.position(row * yRowStride)
                            yBuffer.get(nv21, pos, width)
                            pos += width
                        }
                    }

                    // Chroma → NV21 (VU interleaved)
                    var bulkChroma = false
                    if (uvPixelStride == 2 && uvRowStride == width) {
                        var aliased = uvAliasedForSession
                        if (aliased == null) {
                            aliased = checkUvPlanesAreNV21(uBuffer, vBuffer, width, height)
                            uvAliasedForSession = aliased
                            Log.d(TAG, "Chroma planes NV21-aliased: $aliased")
                        }
                        bulkChroma = aliased
                    }
                    if (bulkChroma) {
                        // The V and U planes alias one interleaved VU buffer
                        // (verified above), so a single bulk copy of the V
                        // plane IS the NV21 chroma sequence — bar the final U
                        // byte, which the V plane's view doesn't cover; patch
                        // it from U.
                        vBuffer.position(0)
                        val vSize = minOf(vBuffer.remaining(), nv21Size - pos)
                        vBuffer.get(nv21, pos, vSize)
                        pos += vSize
                        if (pos < nv21Size && uBuffer.limit() > 0) {
                            nv21[pos] = uBuffer.get(uBuffer.limit() - 1)
                        }
                    } else {
                        // Rare layouts (padded rows / planar chroma): bulk-copy
                        // each chroma row into reused arrays and interleave
                        // from plain byte[]. Per-pixel ByteBuffer.get() here
                        // was ~460k bounds-checked reads per frame.
                        val chromaWidth = width / 2
                        val chromaHeight = height / 2
                        val rowLen = (chromaWidth - 1) * uvPixelStride + 1
                        var rowV = uvRowV
                        var rowU = uvRowU
                        if (rowV == null || rowV.size < rowLen) {
                            rowV = ByteArray(rowLen)
                            uvRowV = rowV
                        }
                        if (rowU == null || rowU.size < rowLen) {
                            rowU = ByteArray(rowLen)
                            uvRowU = rowU
                        }
                        for (row in 0 until chromaHeight) {
                            val base = row * uvRowStride
                            vBuffer.position(base)
                            vBuffer.get(rowV, 0, minOf(rowLen, vBuffer.limit() - base))
                            uBuffer.position(base)
                            uBuffer.get(rowU, 0, minOf(rowLen, uBuffer.limit() - base))
                            var i = 0
                            for (col in 0 until chromaWidth) {
                                nv21[pos++] = rowV[i]
                                nv21[pos++] = rowU[i]
                                i += uvPixelStride
                            }
                        }
                    }

                    // Non-latched lens: mirror the frame in-buffer so the
                    // rotation/mirror arguments below can stay locked.
                    if (flipInBuffer) flipNV21Horizontal(nv21, width, height)

                    var bufs = ingestBuffers
                    if (bufs == null || bufs[0].capacity() != nv21Size) {
                        bufs = arrayOf(
                            ByteBuffer.allocateDirect(nv21Size),
                            ByteBuffer.allocateDirect(nv21Size)
                        )
                        ingestBuffers = bufs
                    }
                    val frameBuffer = bufs[ingestBufferIndex]
                    ingestBufferIndex = (ingestBufferIndex + 1) % 2
                    frameBuffer.clear()
                    frameBuffer.put(nv21)
                    frameBuffer.rewind()

                    // DeepAR is single-threaded: receiveFrame MUST run on the thread
                    // DeepAR was initialized on (the main thread). Only the heavy
                    // YUV->NV21 conversion above runs on the background camera thread.
                    // rotation/mirror are the engine-lifetime locked values — NEVER
                    // the current lens's natural ones (see lockedRotation docs).
                    posted = mainHandler.post {
                        try {
                            if (isCapturing) {
                                deepAR?.receiveFrame(
                                    frameBuffer,
                                    width, height,
                                    lockedRotation,
                                    lockedMirror,
                                    DeepARImageFormat.YUV_420_888,
                                    width
                                )
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "receiveFrame error: ${e.message}")
                        } finally {
                            ingestInFlight.set(false)
                        }
                    }
                } finally {
                    if (!posted) ingestInFlight.set(false)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Frame processing error: ${e.message}")
            } finally {
                image.close()
            }
        }, camHandler)

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (myGeneration != cameraGeneration) {
                    // A newer open/close superseded this session while the HAL
                    // was still opening it — release it, don't touch fields.
                    Log.d(TAG, "Stale onOpened (gen=$myGeneration), closing")
                    camera.close()
                    return
                }
                Log.d(TAG, "Camera opened: $cameraId (gen=$myGeneration)")
                cameraDevice = camera
                startCameraPreview(myGeneration, front)
            }
            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                if (myGeneration != cameraGeneration) return
                Log.w(TAG, "Camera disconnected (gen=$myGeneration)")
                cameraDevice = null
                handleCameraFailure(myGeneration, front, isRetry, "disconnected")
            }
            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                if (myGeneration != cameraGeneration) return
                Log.e(TAG, "Camera error: $error (gen=$myGeneration)")
                cameraDevice = null
                handleCameraFailure(myGeneration, front, isRetry, "error $error")
            }
        }, camHandler)
    }

    // One retry with a short delay covers transient open failures (e.g. the
    // previous device's HAL teardown still in flight); a second failure is
    // surfaced to Dart instead of leaving a silently frozen preview.
    // Runs on the CAMERA thread (device state callbacks) — result completion
    // must go through the generation-guarded overloads.
    private fun handleCameraFailure(generation: Int, front: Boolean, wasRetry: Boolean, why: String) {
        if (!wasRetry) {
            Log.w(TAG, "Camera $why (gen=$generation) — retrying in 300ms")
            mainHandler.postDelayed({
                if (generation == cameraGeneration && isCapturing) {
                    try {
                        openCamera(front, isRetry = true)
                    } catch (e: Exception) {
                        // openCamera throws CameraAccessException when the
                        // camera service itself is down — exactly when retries
                        // fire. Uncaught here it would crash the main looper.
                        Log.e(TAG, "Camera retry failed: ${e.message}", e)
                        failPendingCameraResult("CAMERA_FAILED", e.message)
                    }
                }
            }, 300)
        } else {
            Log.e(TAG, "Camera $why after retry — giving up")
            failPendingCameraResult(generation, "CAMERA_FAILED", "Camera $why")
        }
    }

    // Runs on the CAMERA thread — result completion must go through the
    // generation-guarded overloads (re-checked on the main thread).
    private fun startCameraPreview(generation: Int, front: Boolean) {
        if (generation != cameraGeneration) return
        val camera = cameraDevice ?: return
        val reader = imageReader ?: return
        val camHandler = cameraHandler ?: mainHandler

        try {
            val captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder.addTarget(reader.surface)
            captureRequestBuilder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            )

            camera.createCaptureSession(
                listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (generation != cameraGeneration) {
                            Log.d(TAG, "Stale onConfigured (gen=$generation), closing")
                            try { session.close() } catch (_: Exception) {}
                            return
                        }
                        Log.d(TAG, "Camera capture session configured (gen=$generation)")
                        captureSession = session
                        try {
                            session.setRepeatingRequest(
                                captureRequestBuilder.build(),
                                null,
                                camHandler
                            )
                            completePendingCameraResult(generation, front)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to start repeating request: ${e.message}")
                            failPendingCameraResult(generation, "SESSION_FAILED", e.message)
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Camera capture session configuration failed (gen=$generation)")
                        failPendingCameraResult(generation, "CONFIGURE_FAILED", "Capture session configuration failed")
                    }
                },
                camHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create camera preview: ${e.message}", e)
            failPendingCameraResult(generation, "PREVIEW_FAILED", e.message)
        }
    }

    private fun closeCamera() {
        // Invalidate every outstanding callback from the session being closed
        // BEFORE closing, so late onDisconnected/onError can't clobber the
        // fields of whatever session comes next.
        ++cameraGeneration
        try {
            captureSession?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing capture session: ${e.message}")
        }
        captureSession = null
        try {
            cameraDevice?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing camera device: ${e.message}")
        }
        cameraDevice = null
        try {
            imageReader?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing image reader: ${e.message}")
        }
        imageReader = null
    }

    /**
     * Horizontally mirrors an NV21 frame in place (sensor-space flip).
     *
     * Y plane: each row's bytes are reversed. Chroma plane: each row's VU
     * PAIRS are reversed while V stays before U inside every pair. Row-local
     * swaps only — cache friendly, ~n/2 element swaps on the reused array,
     * cheap next to the YUV->NV21 conversion that precedes it.
     */
    private fun flipNV21Horizontal(nv21: ByteArray, width: Int, height: Int) {
        // Y plane
        var rowStart = 0
        for (row in 0 until height) {
            var i = rowStart
            var j = rowStart + width - 1
            while (i < j) {
                val t = nv21[i]; nv21[i] = nv21[j]; nv21[j] = t
                i++; j--
            }
            rowStart += width
        }
        // Interleaved VU plane: width bytes per row = width/2 pairs
        var base = width * height
        for (row in 0 until height / 2) {
            var i = base
            var j = base + width - 2
            while (i < j) {
                val v = nv21[i]; val u = nv21[i + 1]
                nv21[i] = nv21[j]; nv21[i + 1] = nv21[j + 1]
                nv21[j] = v; nv21[j + 1] = u
                i += 2; j -= 2
            }
            base += width
        }
    }

    // MLKit-style aliasing check: advance V by one byte and chop U's last
    // byte — if the two views then compare equal, they alias one
    // VU-interleaved buffer and the NV21 bulk chroma copy is valid. On
    // NV12-ordered or truly planar layouts this returns false and the exact
    // interleave fallback is used instead. Runs once per camera session.
    private fun checkUvPlanesAreNV21(
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        width: Int,
        height: Int
    ): Boolean {
        val vPos = vBuffer.position()
        val uLimit = uBuffer.limit()
        return try {
            vBuffer.position(vPos + 1)
            uBuffer.limit(uLimit - 1)
            vBuffer.remaining() == (width * height / 2 - 2) &&
                vBuffer.compareTo(uBuffer) == 0
        } catch (e: Exception) {
            false
        } finally {
            vBuffer.position(vPos)
            uBuffer.limit(uLimit)
        }
    }

    private fun getCameraId(front: Boolean): String? {
        val manager = cameraManager ?: return null
        val facing = if (front) CameraCharacteristics.LENS_FACING_FRONT
                     else CameraCharacteristics.LENS_FACING_BACK
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) == facing) {
                return id
            }
        }
        return null
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Send a DeepAR-processed frame to Flutter via EventChannel.
    //  The Image from frameAvailable is RGBA format from off-screen rendering.
    // ──────────────────────────────────────────────────────────────────────

    private fun sendFrameToFlutter(image: Image) {
        nativeFrameCount++

        if (!isCapturing) return
        val sink = frameEventSink ?: return
        // Drop the frame if the previous one is still queued for serialization
        // on the main thread — queueing multi-MB payloads faster than the
        // channel drains them only grows latency and heap.
        if (!sendInFlight.compareAndSet(false, true)) return

        var posted = false
        try {
            val width = image.width
            val height = image.height
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val buffer = plane.buffer
            val size = width * height * pixelStride

            // Reused output buffer. Safe because sink.success() serializes the
            // payload synchronously before the send gate reopens, so the array
            // is never overwritten while the channel still reads it.
            var pixelData = rgbaBytes
            if (pixelData == null || pixelData.size != size) {
                pixelData = ByteArray(size)
                rgbaBytes = pixelData
            }

            if (rowStride == width * pixelStride) {
                // No padding — single bulk copy
                buffer.position(0)
                buffer.get(pixelData, 0, minOf(size, buffer.remaining()))
            } else {
                // Has row padding — copy row by row
                for (row in 0 until height) {
                    buffer.position(row * rowStride)
                    buffer.get(pixelData, row * width * pixelStride, width * pixelStride)
                }
            }

            val frameMap = HashMap<String, Any>()
            frameMap["data"] = pixelData
            frameMap["width"] = width
            frameMap["height"] = height
            frameMap["format"] = "rgba"
            frameMap["timestamp"] = System.currentTimeMillis()

            if (nativeFrameCount <= 3 || nativeFrameCount % 200 == 0) {
                Log.d(TAG, "Frame #$nativeFrameCount: ${width}x${height}, stride=$rowStride")
            }

            posted = mainHandler.post {
                try {
                    sink.success(frameMap)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send frame to Flutter: ${e.message}")
                } finally {
                    sendInFlight.set(false)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting frame data: ${e.message}")
        } finally {
            if (!posted) sendInFlight.set(false)
        }
    }
}
