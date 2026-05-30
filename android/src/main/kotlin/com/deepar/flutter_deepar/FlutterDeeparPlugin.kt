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
    private var frameEventSink: EventChannel.EventSink? = null
    private var applicationContext: Context? = null
    private var activity: Activity? = null

    // DeepAR engine
    private var deepAR: DeepAR? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isSdkInitialized = false
    private var isCapturing = false
    private var nativeFrameCount = 0
    private var isFrontCamera = true

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
    // ──────────────────────────────────────────────────────────────────────

    private fun initializeDeepAR(licenseKey: String, result: MethodChannel.Result) {
        val ctx = applicationContext ?: run {
            result.error("NO_CONTEXT", "Application context is null", null)
            return
        }

        try {
            Log.d(TAG, "Initializing DeepAR SDK...")

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
                    Log.d(TAG, "DeepAR SDK initialized callback")
                    isSdkInitialized = true
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

            isSdkInitialized = true
            nativeFrameCount = 0
            Log.d(TAG, "DeepAR init complete (offscreen ${outputWidth}x${outputHeight})")
            result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize DeepAR: ${e.message}", e)
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
        try {
            deepAR?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing DeepAR: ${e.message}")
        }
        deepAR = null
        isSdkInitialized = false
        nativeFrameCount = 0
        frameEventSink = null
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Camera2 API — open camera, feed YUV frames to DeepAR
    // ──────────────────────────────────────────────────────────────────────

    private fun openCamera(front: Boolean) {
        val manager = cameraManager ?: return
        val cameraId = getCameraId(front) ?: return
        val ctx = activity ?: applicationContext ?: return

        if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Camera permission not granted")
            return
        }

        // ImageReader with 4 buffers to prevent "Unable to acquire buffer" warnings
        imageReader = ImageReader.newInstance(
            cameraWidth, cameraHeight,
            ImageFormat.YUV_420_888, 4
        )
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (!isCapturing) return@setOnImageAvailableListener

                // Convert YUV_420_888 to tightly packed NV21 byte array to guarantee
                // correct chroma interleaving across all Android devices.
                val width = image.width
                val height = image.height
                val nv21 = ByteArray(width * height * 3 / 2)

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

                // Copy UV planes (V then U for NV21)
                for (row in 0 until height / 2) {
                    for (col in 0 until width / 2) {
                        val uvOffset = row * uvRowStride + col * uvPixelStride
                        nv21[pos++] = vBuffer.get(uvOffset)
                        nv21[pos++] = uBuffer.get(uvOffset)
                    }
                }

                val frameBuffer = ByteBuffer.allocateDirect(nv21.size)
                frameBuffer.put(nv21)
                frameBuffer.rewind()

                deepAR?.receiveFrame(
                    frameBuffer,
                    width, height,
                    if (front) 270 else 90,
                    front,
                    DeepARImageFormat.YUV_420_888,
                    width
                )
            } catch (e: Exception) {
                Log.w(TAG, "Frame processing error: ${e.message}")
            } finally {
                image.close()
            }
        }, mainHandler)

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
        }, mainHandler)
    }

    private fun startCameraPreview() {
        val camera = cameraDevice ?: return
        val reader = imageReader ?: return

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
                                mainHandler
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to start repeating request: ${e.message}")
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Camera capture session configuration failed")
                    }
                },
                mainHandler
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

        try {
            val width = image.width
            val height = image.height
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride

            // Handle row padding: rowStride may be larger than width * pixelStride
            val buffer = plane.buffer
            val pixelData: ByteArray

            if (rowStride == width * pixelStride) {
                // No padding — fast path
                pixelData = ByteArray(buffer.remaining())
                buffer.get(pixelData)
            } else {
                // Has row padding — copy row by row
                pixelData = ByteArray(width * height * pixelStride)
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

            mainHandler.post {
                try {
                    sink.success(frameMap)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send frame to Flutter: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting frame data: ${e.message}")
        }
    }
}
