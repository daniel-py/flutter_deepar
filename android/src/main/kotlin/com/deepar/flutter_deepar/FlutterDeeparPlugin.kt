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

    // Whether this camera session's U/V planes alias one NV21-ordered (VU)
    // interleaved buffer, making the bulk chroma copy valid. Checked once per
    // session (the layout is fixed per device+session); null = not yet checked.
    // @Volatile because it is reset on the main thread (openCamera) and used
    // on the camera thread.
    @Volatile private var uvPlanesAreNV21: Boolean? = null

    // Camera2 API
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraManager: CameraManager? = null

    // Camera sensor captures in landscape orientation (1280x720).
    // DeepAR rotates it 270° to portrait.
    private val cameraWidth = 1280
    private val cameraHeight = 720

    // DeepAR offscreen output must be portrait (720x1280) to match
    // the rotated frame orientation that consumers expect.
    private val outputWidth = 720
    private val outputHeight = 1280

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
                initializeDeepAR(licenseKey, result)
            }
            "startCapture" -> startCapture(result)
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
                    try {
                        sendFrameToFlutter(image)
                    } finally {
                        image.close()
                    }
                }
                override fun error(errorType: ARErrorType?, message: String?) {
                    Log.e(TAG, "DeepAR error: $errorType — $message")
                }
                override fun effectSwitched(slot: String?) {
                    Log.d(TAG, "DeepAR effect switched: $slot")
                }
            })

            // Enable off-screen rendering so frameAvailable callback fires.
            // Output is portrait (720x1280) since the camera frame is rotated 270°.
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

    private fun startCapture(result: MethodChannel.Result) {
        try {
            Log.d(TAG, "startCapture: initialized=$isSdkInitialized")
            isCapturing = true
            nativeFrameCount = 0
            startCameraThread()
            openCamera(isFrontCamera)
            result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start capture: ${e.message}", e)
            result.error("START_FAILED", e.message, null)
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Stop camera capture
    // ──────────────────────────────────────────────────────────────────────

    private fun stopCapture(result: MethodChannel.Result) {
        try {
            Log.d(TAG, "Stopping capture...")
            isCapturing = false
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
        try {
            isFrontCamera = !isFrontCamera
            closeCamera()
            openCamera(isFrontCamera)
            Log.d(TAG, "Camera switched to ${if (isFrontCamera) "FRONT" else "BACK"}")
            result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to switch camera: ${e.message}", e)
            result.error("SWITCH_FAILED", e.message, null)
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

    private fun openCamera(front: Boolean) {
        val manager = cameraManager ?: return
        // Ensure the background thread exists (e.g. if openCamera is reached
        // via switchCamera without a fresh startCapture).
        if (cameraHandler == null) startCameraThread()
        val camHandler = cameraHandler ?: return
        val cameraId = getCameraId(front) ?: return
        val ctx = activity ?: applicationContext ?: return

        if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Camera permission not granted")
            return
        }

        // New session — the chroma layout must be re-checked on its first frame.
        uvPlanesAreNV21 = null

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
                        var aliased = uvPlanesAreNV21
                        if (aliased == null) {
                            aliased = checkUvPlanesAreNV21(uBuffer, vBuffer, width, height)
                            uvPlanesAreNV21 = aliased
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

                    val rotation = if (front) 270 else 90
                    // DeepAR is single-threaded: receiveFrame MUST run on the thread
                    // DeepAR was initialized on (the main thread). Only the heavy
                    // YUV->NV21 conversion above runs on the background camera thread.
                    posted = mainHandler.post {
                        try {
                            if (isCapturing) {
                                deepAR?.receiveFrame(
                                    frameBuffer,
                                    width, height,
                                    rotation,
                                    front,
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
                Log.d(TAG, "Camera opened: $cameraId")
                cameraDevice = camera
                startCameraPreview()
            }
            override fun onDisconnected(camera: CameraDevice) {
                Log.d(TAG, "Camera disconnected")
                camera.close()
                cameraDevice = null
            }
            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "Camera error: $error")
                camera.close()
                cameraDevice = null
            }
        }, camHandler)
    }

    private fun startCameraPreview() {
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
                        Log.d(TAG, "Camera capture session configured")
                        captureSession = session
                        try {
                            session.setRepeatingRequest(
                                captureRequestBuilder.build(),
                                null,
                                camHandler
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to start repeating request: ${e.message}")
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Camera capture session configuration failed")
                    }
                },
                camHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create camera preview: ${e.message}", e)
        }
    }

    private fun closeCamera() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing camera: ${e.message}")
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
