import Flutter
import UIKit
import DeepAR
import AVFoundation

/// FlutterDeeparPlugin — Flutter plugin wrapping the DeepAR augmented reality SDK.
///
/// Provides camera capture via AVFoundation, AR effect loading, and processed frame
/// output via EventChannel. Frames are delivered as BGRA byte arrays suitable
/// for direct consumption or forwarding to a live-streaming SDK.
public class FlutterDeeparPlugin: NSObject, FlutterPlugin, FlutterStreamHandler {

    // Flutter bindings
    private var methodChannel: FlutterMethodChannel?
    private var eventChannel: FlutterEventChannel?
    var frameEventSink: FlutterEventSink?

    // DeepAR engine
    private var deepAR: DeepAR?
    private var captureSession: AVCaptureSession?
    private var videoOutput: AVCaptureVideoDataOutput?
    private var isCapturing = false
    private var isSdkInitialized = false
    private var isFrontCamera = true
    private var nativeFrameCount = 0
    private let sessionQueue = DispatchQueue(label: "com.deepar.flutter_deepar.session")

    // Pending init result — held until DeepAR's didInitialize fires
    private var pendingInitResult: FlutterResult?

    // ──────────────────────────────────────────────────────────────────────
    //  Plugin registration
    // ──────────────────────────────────────────────────────────────────────

    public static func register(with registrar: FlutterPluginRegistrar) {
        let instance = FlutterDeeparPlugin()

        instance.methodChannel = FlutterMethodChannel(
            name: "flutter_deepar",
            binaryMessenger: registrar.messenger()
        )
        instance.methodChannel?.setMethodCallHandler(instance.handle)

        instance.eventChannel = FlutterEventChannel(
            name: "flutter_deepar/frames",
            binaryMessenger: registrar.messenger()
        )
        instance.eventChannel?.setStreamHandler(instance)

        registrar.addMethodCallDelegate(instance, channel: instance.methodChannel!)
    }

    // ──────────────────────────────────────────────────────────────────────
    //  FlutterStreamHandler
    // ──────────────────────────────────────────────────────────────────────

    public func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        NSLog("[FlutterDeepAR] Frame EventChannel: onListen")
        frameEventSink = events
        return nil
    }

    public func onCancel(withArguments arguments: Any?) -> FlutterError? {
        NSLog("[FlutterDeepAR] Frame EventChannel: onCancel")
        frameEventSink = nil
        return nil
    }

    // ──────────────────────────────────────────────────────────────────────
    //  MethodChannel dispatch
    // ──────────────────────────────────────────────────────────────────────

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "initializeDeepAR":
            guard let args = call.arguments as? [String: Any],
                  let licenseKey = args["licenseKey"] as? String else {
                result(FlutterError(code: "INVALID_ARG", message: "License key is required", details: nil))
                return
            }
            initializeDeepAR(licenseKey: licenseKey, result: result)
        case "startCapture":
            startCapture(result: result)
        case "stopCapture":
            stopCapture(result: result)
        case "loadEffect":
            let effectPath = (call.arguments as? [String: Any])?["effectPath"] as? String ?? ""
            loadEffect(effectPath: effectPath, result: result)
        case "switchCamera":
            switchCamera(result: result)
        case "destroyDeepAR":
            destroyDeepAR(result: result)
        default:
            result(FlutterMethodNotImplemented)
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Initialize DeepAR SDK
    //  NOTE: DeepAR's initializeOffscreen is asynchronous.  We hold the
    //  FlutterResult until the `didInitialize` delegate callback fires so
    //  the Dart side doesn't call startCapture on an engine that hasn't
    //  finished setup yet.  In release builds (AOT) the Dart code runs
    //  fast enough to hit this race condition consistently.
    // ──────────────────────────────────────────────────────────────────────

    private func initializeDeepAR(licenseKey: String, result: @escaping FlutterResult) {
        NSLog("[FlutterDeepAR] Initializing DeepAR SDK...")

        deepAR = DeepAR()
        deepAR?.setLicenseKey(licenseKey)
        deepAR?.delegate = self
        // Disable live mode and initialize off-screen rendering.
        // Output is portrait 720x1280 (AVFoundation handles rotation via connection.videoOrientation).
        deepAR?.changeLiveMode(false)

        nativeFrameCount = 0

        // Hold the result — we'll complete it in didInitialize()
        pendingInitResult = result

        deepAR?.initializeOffscreen(withWidth: 720, height: 1280)

        // Safety timeout: if didInitialize never fires within 5s, resolve anyway
        // so the Dart side isn't stuck waiting forever.
        DispatchQueue.main.asyncAfter(deadline: .now() + 5.0) { [weak self] in
            guard let self = self, let pending = self.pendingInitResult else { return }
            NSLog("[FlutterDeepAR] Init timeout — resolving pending result")
            self.isSdkInitialized = true
            self.pendingInitResult = nil
            pending(true)
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Start/Stop Capture
    // ──────────────────────────────────────────────────────────────────────

    private func startCapture(result: @escaping FlutterResult) {
        NSLog("[FlutterDeepAR] Starting capture...")
        isCapturing = true
        nativeFrameCount = 0
        setupCameraSession(useFront: isFrontCamera)
        NSLog("[FlutterDeepAR] Capture started")
        result(true)
    }

    private func stopCapture(result: @escaping FlutterResult) {
        NSLog("[FlutterDeepAR] Stopping capture...")
        isCapturing = false
        sessionQueue.async { [weak self] in
            self?.captureSession?.stopRunning()
        }
        NSLog("[FlutterDeepAR] Capture stopped")
        result(true)
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Load Effect
    // ──────────────────────────────────────────────────────────────────────

    private func loadEffect(effectPath: String, result: @escaping FlutterResult) {
        if effectPath.isEmpty || effectPath == "None" {
            NSLog("[FlutterDeepAR] Clearing effect")
            deepAR?.switchEffect(withSlot: "effect", path: nil)
        } else {
            NSLog("[FlutterDeepAR] Loading effect: \(effectPath)")
            // effectPath is like "effects/filter_name.deepar" — resolve from main bundle
            if let fullPath = Bundle.main.path(forResource: effectPath, ofType: nil) {
                deepAR?.switchEffect(withSlot: "effect", path: fullPath)
            } else {
                // Try without extension in case it's passed with .deepar already
                let name = (effectPath as NSString).deletingPathExtension
                let ext = (effectPath as NSString).pathExtension
                if let fullPath = Bundle.main.path(forResource: name, ofType: ext.isEmpty ? "deepar" : ext) {
                    deepAR?.switchEffect(withSlot: "effect", path: fullPath)
                } else {
                    NSLog("[FlutterDeepAR] Effect file not found: \(effectPath)")
                }
            }
        }
        result(true)
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Switch Camera
    // ──────────────────────────────────────────────────────────────────────

    private func switchCamera(result: @escaping FlutterResult) {
        NSLog("[FlutterDeepAR] Switching camera...")
        isFrontCamera = !isFrontCamera
        sessionQueue.async { [weak self] in
            self?.captureSession?.stopRunning()
            DispatchQueue.main.async {
                self?.setupCameraSession(useFront: self?.isFrontCamera ?? true)
            }
        }
        NSLog("[FlutterDeepAR] Camera switched to \(isFrontCamera ? "FRONT" : "BACK")")
        result(true)
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Destroy
    // ──────────────────────────────────────────────────────────────────────

    private func destroyDeepAR(result: @escaping FlutterResult) {
        NSLog("[FlutterDeepAR] Destroying pipeline...")
        isCapturing = false
        sessionQueue.async { [weak self] in
            self?.captureSession?.stopRunning()
        }
        captureSession = nil
        videoOutput = nil
        deepAR?.shutdown()
        deepAR = nil
        isSdkInitialized = false
        pendingInitResult = nil
        NSLog("[FlutterDeepAR] Pipeline destroyed")
        result(true)
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Camera Session Setup (AVFoundation)
    // ──────────────────────────────────────────────────────────────────────

    private func setupCameraSession(useFront: Bool) {
        let session = AVCaptureSession()
        session.sessionPreset = .hd1280x720

        let position: AVCaptureDevice.Position = useFront ? .front : .back
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position),
              let input = try? AVCaptureDeviceInput(device: device) else {
            NSLog("[FlutterDeepAR] Failed to create camera input")
            return
        }

        if session.canAddInput(input) {
            session.addInput(input)
        }

        let output = AVCaptureVideoDataOutput()
        output.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
        ]
        output.setSampleBufferDelegate(self, queue: sessionQueue)
        output.alwaysDiscardsLateVideoFrames = true

        if session.canAddOutput(output) {
            session.addOutput(output)
        }

        // Set orientation
        if let connection = output.connection(with: .video) {
            if connection.isVideoOrientationSupported {
                connection.videoOrientation = .portrait
            }
            // NOTE: Do NOT set connection.isVideoMirrored here.
            // DeepAR handles mirroring via the `mirror` param in enqueueCameraFrame.
            // Setting both would cancel each other out (double mirror = no mirror).
        }

        captureSession = session
        videoOutput = output

        sessionQueue.async {
            session.startRunning()
            NSLog("[FlutterDeepAR] Camera session started")
        }
    }
}

// MARK: - AVCaptureVideoDataOutputSampleBufferDelegate

extension FlutterDeeparPlugin: AVCaptureVideoDataOutputSampleBufferDelegate {
    public func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard isCapturing, isSdkInitialized else { return }
        deepAR?.enqueueCameraFrame(sampleBuffer, mirror: isFrontCamera)
    }
}

// MARK: - DeepARDelegate

extension FlutterDeeparPlugin: DeepARDelegate {
    public func didFinishPreparingForVideoRecording() {}
    public func didStartVideoRecording() {}
    public func didFinishVideoRecording(_ videoFilePath: String!) {}
    public func recordingFailedWithError(_ error: Error!) {}
    public func didTakeScreenshot(_ screenshot: UIImage!) {}
    public func didInitialize() {
        NSLog("[FlutterDeepAR] DeepAR didInitialize callback — engine is ready")
        isSdkInitialized = true
        // Complete the pending init result now that the engine is truly ready
        if let pending = pendingInitResult {
            pendingInitResult = nil
            pending(true)
        }
    }
    public func faceVisiblityDidChange(_ visible: Bool) {}
    public func didFinishShutdown() {
        NSLog("[FlutterDeepAR] DeepAR didFinishShutdown")
    }

    public func frameAvailable(_ sampleBuffer: CMSampleBuffer!) {
        guard isCapturing, let buffer = sampleBuffer else { return }
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(buffer) else { return }
        guard let sink = frameEventSink else { return }

        nativeFrameCount += 1

        CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }

        guard let baseAddress = CVPixelBufferGetBaseAddress(pixelBuffer) else { return }

        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)
        let bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer)
        let totalBytes = bytesPerRow * height

        let data = FlutterStandardTypedData(bytes: Data(bytes: baseAddress, count: totalBytes))

        if nativeFrameCount <= 3 || nativeFrameCount % 200 == 0 {
            NSLog("[FlutterDeepAR] Frame #\(nativeFrameCount): \(width)x\(height), bytes=\(totalBytes)")
        }

        DispatchQueue.main.async {
            sink([
                "data": data,
                "width": width,
                "height": height,
                "format": "bgra",
                "timestamp": Int64(Date().timeIntervalSince1970 * 1000)
            ])
        }
    }
}
