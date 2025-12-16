# VerityPro iOS SDK - Implementation Guide

## Backend Configuration
```swift
let BASE_URL = "http://localhost:8001"
```

## API Endpoints Used
| Endpoint | Purpose |
|----------|---------|
| `POST /v1/kyc/doc/predict` | Single frame detection + classification |
| `POST /v1/kyc/doc/verify-burst` | Multi-frame anti-spoof verification |

---

## Project Structure

```
VerityProSDK/
├── Models/
│   ├── DocumentType.swift
│   ├── VerificationResult.swift
│   └── APIModels.swift
├── Services/
│   ├── CameraService.swift
│   └── VerificationAPIService.swift
├── Views/
│   ├── DocumentSelectionView.swift
│   ├── DocumentCaptureView.swift
│   ├── DocumentPreviewView.swift
│   ├── LivenessCheckView.swift
│   └── ConfirmationView.swift
└── VerityProSDK.swift
```

---

## Models

### DocumentType.swift
```swift
import Foundation

enum DocumentType: String, CaseIterable, Identifiable {
    case passport = "PASSPORT"
    case driversLicense = "DRIVERS_LICENSE"
    case idCard = "ID_CARD"

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .passport: return "Passport"
        case .driversLicense: return "Driver's License"
        case .idCard: return "National ID Card"
        }
    }

    var icon: String {
        switch self {
        case .passport: return "🛂"
        case .driversLicense: return "🚗"
        case .idCard: return "🪪"
        }
    }

    var requiresBackSide: Bool {
        switch self {
        case .passport: return false
        case .driversLicense, .idCard: return true
        }
    }
}

enum DocumentSide: String {
    case front = "FRONT"
    case back = "BACK"
}
```

### APIModels.swift
```swift
import Foundation

// MARK: - Predict Request/Response
struct PredictRequest: Codable {
    let sessionId: String
    let docTypeExpected: String
    let sideExpected: String
    let imageJpegBase64: String
}

struct PredictResponse: Codable {
    let docOk: Bool
    let bbox: BoundingBox?
    let docType: String?
    let side: String?
    let nextAction: String?
    let hint: String?
    let confidence: ConfidenceScores?
    let latencyMs: Double?
}

struct BoundingBox: Codable {
    let x: Double
    let y: Double
    let w: Double
    let h: Double
}

struct ConfidenceScores: Codable {
    let detection: Double?
    let classification: Double?
}

// MARK: - Verify Burst Request/Response
struct VerifyBurstRequest: Codable {
    let sessionId: String
    let frames: [String]
    let docTypeExpected: String
    let sideExpected: String
}

struct VerifyBurstResponse: Codable {
    let decision: String
    let spoof: SpoofResult?
    let hint: String?
    let confidence: Double?
    let latencyMs: Double?
}

struct SpoofResult: Codable {
    let score: Double
    let reason: String
}
```

---

## Services

### VerificationAPIService.swift
```swift
import Foundation
import UIKit

class VerificationAPIService {
    static let shared = VerificationAPIService()

    private let baseURL = "http://localhost:8001"
    private let session = URLSession.shared

    private init() {}

    // MARK: - Predict (Detection + Classification)
    func predict(
        sessionId: String,
        docType: DocumentType,
        side: DocumentSide,
        image: UIImage
    ) async throws -> PredictResponse {
        guard let imageData = image.jpegData(compressionQuality: 0.8) else {
            throw APIError.imageConversionFailed
        }

        let base64 = imageData.base64EncodedString()

        let request = PredictRequest(
            sessionId: sessionId,
            docTypeExpected: docType.rawValue,
            sideExpected: side.rawValue,
            imageJpegBase64: base64
        )

        return try await post(
            endpoint: "/v1/kyc/doc/predict",
            body: request,
            responseType: PredictResponse.self
        )
    }

    // MARK: - Verify Burst (Anti-Spoof)
    func verifyBurst(
        sessionId: String,
        frames: [UIImage],
        docType: DocumentType,
        side: DocumentSide
    ) async throws -> VerifyBurstResponse {
        let base64Frames = frames.compactMap { image -> String? in
            guard let data = image.jpegData(compressionQuality: 0.7) else { return nil }
            return data.base64EncodedString()
        }

        guard base64Frames.count >= 3 else {
            throw APIError.insufficientFrames
        }

        let request = VerifyBurstRequest(
            sessionId: sessionId,
            frames: base64Frames,
            docTypeExpected: docType.rawValue,
            sideExpected: side.rawValue
        )

        return try await post(
            endpoint: "/v1/kyc/doc/verify-burst",
            body: request,
            responseType: VerifyBurstResponse.self
        )
    }

    // MARK: - Generic POST
    private func post<T: Encodable, R: Decodable>(
        endpoint: String,
        body: T,
        responseType: R.Type
    ) async throws -> R {
        guard let url = URL(string: baseURL + endpoint) else {
            throw APIError.invalidURL
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(body)
        request.timeoutInterval = 30

        let (data, response) = try await session.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse else {
            throw APIError.invalidResponse
        }

        guard (200...299).contains(httpResponse.statusCode) else {
            throw APIError.serverError(statusCode: httpResponse.statusCode)
        }

        return try JSONDecoder().decode(R.self, from: data)
    }
}

enum APIError: Error, LocalizedError {
    case invalidURL
    case imageConversionFailed
    case insufficientFrames
    case invalidResponse
    case serverError(statusCode: Int)

    var errorDescription: String? {
        switch self {
        case .invalidURL: return "Invalid URL"
        case .imageConversionFailed: return "Failed to convert image"
        case .insufficientFrames: return "Need at least 3 frames for verification"
        case .invalidResponse: return "Invalid server response"
        case .serverError(let code): return "Server error: \(code)"
        }
    }
}
```

### CameraService.swift
```swift
import AVFoundation
import UIKit

protocol CameraServiceDelegate: AnyObject {
    func cameraService(_ service: CameraService, didCaptureFrame image: UIImage)
    func cameraService(_ service: CameraService, didCapturePhoto image: UIImage)
    func cameraServiceDidFail(_ service: CameraService, error: Error)
}

class CameraService: NSObject {
    weak var delegate: CameraServiceDelegate?

    private let captureSession = AVCaptureSession()
    private let photoOutput = AVCapturePhotoOutput()
    private let videoOutput = AVCaptureVideoDataOutput()
    private let sessionQueue = DispatchQueue(label: "camera.session")
    private let processingQueue = DispatchQueue(label: "camera.processing")

    var previewLayer: AVCaptureVideoPreviewLayer?

    private var frameCollectionEnabled = false
    private var collectedFrames: [UIImage] = []
    private let maxFrames = 10

    // MARK: - Setup
    func setupCamera() {
        sessionQueue.async { [weak self] in
            self?.configureSession()
        }
    }

    private func configureSession() {
        captureSession.beginConfiguration()
        captureSession.sessionPreset = .high

        // Add video input (rear camera)
        guard let videoDevice = AVCaptureDevice.default(
            .builtInWideAngleCamera,
            for: .video,
            position: .back
        ) else {
            delegate?.cameraServiceDidFail(self, error: CameraError.noCameraAvailable)
            return
        }

        do {
            let videoInput = try AVCaptureDeviceInput(device: videoDevice)
            if captureSession.canAddInput(videoInput) {
                captureSession.addInput(videoInput)
            }
        } catch {
            delegate?.cameraServiceDidFail(self, error: error)
            return
        }

        // Add photo output
        if captureSession.canAddOutput(photoOutput) {
            captureSession.addOutput(photoOutput)
        }

        // Add video output for frame capture
        videoOutput.setSampleBufferDelegate(self, queue: processingQueue)
        if captureSession.canAddOutput(videoOutput) {
            captureSession.addOutput(videoOutput)
        }

        captureSession.commitConfiguration()

        // Create preview layer
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.previewLayer = AVCaptureVideoPreviewLayer(session: self.captureSession)
            self.previewLayer?.videoGravity = .resizeAspectFill
        }
    }

    func startSession() {
        sessionQueue.async { [weak self] in
            self?.captureSession.startRunning()
        }
    }

    func stopSession() {
        sessionQueue.async { [weak self] in
            self?.captureSession.stopRunning()
        }
    }

    // MARK: - Capture
    func capturePhoto() {
        let settings = AVCapturePhotoSettings()
        settings.flashMode = .off
        photoOutput.capturePhoto(with: settings, delegate: self)
    }

    // MARK: - Frame Collection for Anti-Spoof
    func startFrameCollection() {
        collectedFrames.removeAll()
        frameCollectionEnabled = true
    }

    func stopFrameCollection() -> [UIImage] {
        frameCollectionEnabled = false
        return collectedFrames
    }
}

// MARK: - Photo Capture Delegate
extension CameraService: AVCapturePhotoCaptureDelegate {
    func photoOutput(
        _ output: AVCapturePhotoOutput,
        didFinishProcessingPhoto photo: AVCapturePhoto,
        error: Error?
    ) {
        if let error = error {
            delegate?.cameraServiceDidFail(self, error: error)
            return
        }

        guard let imageData = photo.fileDataRepresentation(),
              let image = UIImage(data: imageData) else {
            delegate?.cameraServiceDidFail(self, error: CameraError.photoProcessingFailed)
            return
        }

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.delegate?.cameraService(self, didCapturePhoto: image)
        }
    }
}

// MARK: - Video Frame Delegate
extension CameraService: AVCaptureVideoDataOutputSampleBufferDelegate {
    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

        let ciImage = CIImage(cvPixelBuffer: pixelBuffer)
        let context = CIContext()

        guard let cgImage = context.createCGImage(ciImage, from: ciImage.extent) else { return }

        let image = UIImage(cgImage: cgImage)

        // Send frame for real-time detection
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.delegate?.cameraService(self, didCaptureFrame: image)
        }

        // Collect frames for anti-spoof
        if frameCollectionEnabled && collectedFrames.count < maxFrames {
            collectedFrames.append(image)
        }
    }
}

enum CameraError: Error {
    case noCameraAvailable
    case photoProcessingFailed
}
```

---

## Views (SwiftUI)

### Step 1: DocumentSelectionView.swift
```swift
import SwiftUI

struct DocumentSelectionView: View {
    @Binding var selectedDocument: DocumentType?
    let onContinue: () -> Void

    var body: some View {
        VStack(spacing: 24) {
            // Header
            VStack(spacing: 8) {
                Text("Select Your Document")
                    .font(.title)
                    .fontWeight(.bold)

                Text("Choose the type of identity document you want to verify")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(.top, 40)

            // Document Options
            VStack(spacing: 16) {
                ForEach(DocumentType.allCases) { docType in
                    DocumentCard(
                        docType: docType,
                        isSelected: selectedDocument == docType,
                        onTap: { selectedDocument = docType }
                    )
                }
            }
            .padding(.horizontal)

            Spacer()

            // Continue Button
            Button(action: onContinue) {
                Text("Continue")
                    .font(.headline)
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(selectedDocument != nil ? Color.blue : Color.gray)
                    .cornerRadius(12)
            }
            .disabled(selectedDocument == nil)
            .padding(.horizontal)
            .padding(.bottom, 24)
        }
    }
}

struct DocumentCard: View {
    let docType: DocumentType
    let isSelected: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 16) {
                Text(docType.icon)
                    .font(.system(size: 40))

                VStack(alignment: .leading, spacing: 4) {
                    Text(docType.displayName)
                        .font(.headline)
                        .foregroundColor(.primary)

                    if docType.requiresBackSide {
                        Text("Front + Back required")
                            .font(.caption)
                            .foregroundColor(.orange)
                    }
                }

                Spacer()

                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.title2)
                    .foregroundColor(isSelected ? .blue : .gray)
            }
            .padding()
            .background(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(isSelected ? Color.blue : Color.gray.opacity(0.3), lineWidth: 2)
                    .background(
                        RoundedRectangle(cornerRadius: 12)
                            .fill(isSelected ? Color.blue.opacity(0.1) : Color.clear)
                    )
            )
        }
        .buttonStyle(PlainButtonStyle())
    }
}
```

### Step 2: DocumentCaptureView.swift
```swift
import SwiftUI
import AVFoundation

struct DocumentCaptureView: View {
    let docType: DocumentType
    let side: DocumentSide
    let sessionId: String
    let onCapture: (UIImage, [UIImage]) -> Void
    let onBack: () -> Void

    @StateObject private var viewModel = DocumentCaptureViewModel()

    var body: some View {
        ZStack {
            // Camera Preview
            CameraPreviewView(cameraService: viewModel.cameraService)
                .ignoresSafeArea()

            VStack {
                // Header
                HStack {
                    Button(action: onBack) {
                        Image(systemName: "chevron.left")
                            .font(.title2)
                            .foregroundColor(.white)
                            .padding()
                    }

                    Spacer()

                    Text("\(side == .front ? "Front" : "Back") of \(docType.displayName)")
                        .font(.headline)
                        .foregroundColor(.white)

                    Spacer()

                    Color.clear.frame(width: 44, height: 44)
                }
                .padding(.horizontal)
                .background(Color.black.opacity(0.6))

                Spacer()

                // Document Frame Overlay
                DocumentFrameOverlay(isDetected: viewModel.isDocumentDetected)
                    .padding(.horizontal, 24)

                // Detection Hint
                HintBanner(
                    text: viewModel.hint,
                    isSuccess: viewModel.isDocumentDetected
                )
                .padding(.bottom, 20)

                Spacer()

                // Capture Button
                VStack(spacing: 16) {
                    CaptureButton(
                        isEnabled: viewModel.isDocumentDetected,
                        isCapturing: viewModel.isCapturing,
                        onCapture: {
                            viewModel.capturePhoto { image, frames in
                                onCapture(image, frames)
                            }
                        }
                    )

                    // Confidence Indicator
                    if let confidence = viewModel.confidence {
                        ConfidenceBar(confidence: confidence)
                    }
                }
                .padding(.bottom, 40)
                .background(Color.black.opacity(0.6))
            }
        }
        .onAppear {
            viewModel.startCamera(docType: docType, side: side, sessionId: sessionId)
        }
        .onDisappear {
            viewModel.stopCamera()
        }
    }
}

// MARK: - ViewModel
class DocumentCaptureViewModel: ObservableObject {
    @Published var isDocumentDetected = false
    @Published var hint = "Position document within frame"
    @Published var confidence: Double?
    @Published var isCapturing = false

    let cameraService = CameraService()
    private var docType: DocumentType?
    private var side: DocumentSide?
    private var sessionId: String?
    private var detectionTimer: Timer?

    func startCamera(docType: DocumentType, side: DocumentSide, sessionId: String) {
        self.docType = docType
        self.side = side
        self.sessionId = sessionId

        cameraService.delegate = self
        cameraService.setupCamera()
        cameraService.startSession()
        cameraService.startFrameCollection()

        // Start detection polling
        detectionTimer = Timer.scheduledTimer(withTimeInterval: 0.8, repeats: true) { [weak self] _ in
            self?.runDetection()
        }
    }

    func stopCamera() {
        detectionTimer?.invalidate()
        cameraService.stopSession()
    }

    private func runDetection() {
        // Detection is triggered by frame callback
    }

    func capturePhoto(completion: @escaping (UIImage, [UIImage]) -> Void) {
        isCapturing = true
        cameraService.capturePhoto()
        // Photo delegate will handle the result
    }
}

extension DocumentCaptureViewModel: CameraServiceDelegate {
    func cameraService(_ service: CameraService, didCaptureFrame image: UIImage) {
        guard let docType = docType, let side = side, let sessionId = sessionId else { return }

        Task {
            do {
                let result = try await VerificationAPIService.shared.predict(
                    sessionId: sessionId,
                    docType: docType,
                    side: side,
                    image: image
                )

                await MainActor.run {
                    self.isDocumentDetected = result.docOk
                    self.hint = result.hint ?? "Position document within frame"
                    self.confidence = result.confidence?.detection
                }
            } catch {
                await MainActor.run {
                    self.hint = "Detection unavailable"
                }
            }
        }
    }

    func cameraService(_ service: CameraService, didCapturePhoto image: UIImage) {
        let frames = service.stopFrameCollection()
        isCapturing = false
        // Callback handled in view
    }

    func cameraServiceDidFail(_ service: CameraService, error: Error) {
        hint = "Camera error: \(error.localizedDescription)"
    }
}

// MARK: - Supporting Views
struct CameraPreviewView: UIViewRepresentable {
    let cameraService: CameraService

    func makeUIView(context: Context) -> UIView {
        let view = UIView()

        DispatchQueue.main.async {
            if let previewLayer = cameraService.previewLayer {
                previewLayer.frame = view.bounds
                view.layer.addSublayer(previewLayer)
            }
        }

        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        cameraService.previewLayer?.frame = uiView.bounds
    }
}

struct DocumentFrameOverlay: View {
    let isDetected: Bool

    var body: some View {
        RoundedRectangle(cornerRadius: 12)
            .strokeBorder(
                isDetected ? Color.green : Color.white.opacity(0.5),
                lineWidth: 3
            )
            .aspectRatio(1.586, contentMode: .fit) // ID card ratio
            .shadow(color: isDetected ? .green.opacity(0.5) : .clear, radius: 10)
    }
}

struct HintBanner: View {
    let text: String
    let isSuccess: Bool

    var body: some View {
        Text(text)
            .font(.subheadline)
            .fontWeight(.medium)
            .foregroundColor(.white)
            .padding(.horizontal, 24)
            .padding(.vertical, 12)
            .background(
                Capsule()
                    .fill(isSuccess ? Color.green : Color.orange)
            )
    }
}

struct CaptureButton: View {
    let isEnabled: Bool
    let isCapturing: Bool
    let onCapture: () -> Void

    var body: some View {
        Button(action: onCapture) {
            ZStack {
                Circle()
                    .stroke(Color.white, lineWidth: 4)
                    .frame(width: 80, height: 80)

                Circle()
                    .fill(isEnabled ? Color.green : Color.gray)
                    .frame(width: 68, height: 68)

                if isCapturing {
                    ProgressView()
                        .progressViewStyle(CircularProgressViewStyle(tint: .white))
                } else {
                    Text("CAPTURE")
                        .font(.caption)
                        .fontWeight(.bold)
                        .foregroundColor(.white)
                }
            }
        }
        .disabled(!isEnabled || isCapturing)
    }
}

struct ConfidenceBar: View {
    let confidence: Double

    var body: some View {
        HStack {
            GeometryReader { geometry in
                ZStack(alignment: .leading) {
                    RoundedRectangle(cornerRadius: 4)
                        .fill(Color.white.opacity(0.2))

                    RoundedRectangle(cornerRadius: 4)
                        .fill(Color.green)
                        .frame(width: geometry.size.width * confidence)
                }
            }
            .frame(width: 150, height: 8)

            Text("\(Int(confidence * 100))%")
                .font(.caption)
                .foregroundColor(.white)
        }
    }
}
```

### Step 2.1 & 4.1: DocumentPreviewView.swift
```swift
import SwiftUI

struct DocumentPreviewView: View {
    let image: UIImage
    let burstFrames: [UIImage]
    let docType: DocumentType
    let side: DocumentSide
    let sessionId: String
    let onContinue: () -> Void
    let onRetake: () -> Void

    @StateObject private var viewModel = DocumentPreviewViewModel()

    var body: some View {
        VStack(spacing: 0) {
            // Header
            VStack(spacing: 8) {
                Text("\(side == .front ? "Front" : "Back") of Document")
                    .font(.title2)
                    .fontWeight(.bold)

                Text("Review your captured image")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
            }
            .padding()
            .frame(maxWidth: .infinity)
            .background(Color(.systemBackground))

            // Image Preview
            ZStack {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .cornerRadius(12)
                    .shadow(radius: 8)

                if viewModel.isVerifying {
                    VerificationOverlay()
                }
            }
            .padding()

            // Verification Results
            if !viewModel.isVerifying {
                VerificationResultsView(
                    classification: viewModel.classificationResult,
                    antiSpoof: viewModel.antiSpoofResult
                )
                .padding(.horizontal)
            }

            // Error Message
            if let error = viewModel.error {
                ErrorBanner(message: error)
                    .padding()
            }

            Spacer()

            // Action Buttons
            HStack(spacing: 12) {
                Button(action: onRetake) {
                    Text("Retake Photo")
                        .font(.headline)
                        .foregroundColor(.gray)
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(
                            RoundedRectangle(cornerRadius: 12)
                                .stroke(Color.gray, lineWidth: 2)
                        )
                }

                Button(action: onContinue) {
                    Text(viewModel.isVerified ? "Continue" : "Retry Required")
                        .font(.headline)
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(
                            RoundedRectangle(cornerRadius: 12)
                                .fill(viewModel.isVerified ? Color.blue : Color.gray)
                        )
                }
                .disabled(!viewModel.isVerified || viewModel.isVerifying)
            }
            .padding()
            .background(Color(.systemBackground))
        }
        .onAppear {
            viewModel.verify(
                image: image,
                burstFrames: burstFrames,
                docType: docType,
                side: side,
                sessionId: sessionId
            )
        }
    }
}

// MARK: - ViewModel
class DocumentPreviewViewModel: ObservableObject {
    @Published var isVerifying = true
    @Published var classificationResult: ClassificationResult?
    @Published var antiSpoofResult: AntiSpoofResult?
    @Published var error: String?

    var isVerified: Bool {
        guard let classification = classificationResult,
              let antiSpoof = antiSpoofResult else { return false }
        return classification.passed && antiSpoof.passed
    }

    func verify(
        image: UIImage,
        burstFrames: [UIImage],
        docType: DocumentType,
        side: DocumentSide,
        sessionId: String
    ) {
        Task {
            await MainActor.run { isVerifying = true; error = nil }

            do {
                // Step 1: Classification
                let predictResult = try await VerificationAPIService.shared.predict(
                    sessionId: sessionId,
                    docType: docType,
                    side: side,
                    image: image
                )

                await MainActor.run {
                    self.classificationResult = ClassificationResult(
                        passed: predictResult.docOk,
                        docType: predictResult.docType ?? "Unknown",
                        side: predictResult.side ?? "Unknown",
                        confidence: predictResult.confidence?.classification ?? 0
                    )
                }

                guard predictResult.docOk else {
                    await MainActor.run {
                        self.error = predictResult.hint ?? "Document validation failed"
                        self.isVerifying = false
                    }
                    return
                }

                // Step 2: Anti-Spoof
                let framesToUse = burstFrames.isEmpty ? [image] : burstFrames
                let spoofResult = try await VerificationAPIService.shared.verifyBurst(
                    sessionId: sessionId,
                    frames: framesToUse,
                    docType: docType,
                    side: side
                )

                await MainActor.run {
                    self.antiSpoofResult = AntiSpoofResult(
                        passed: spoofResult.decision == "PASS",
                        reason: spoofResult.spoof?.reason ?? "Unknown",
                        confidence: 1 - (spoofResult.spoof?.score ?? 0)
                    )

                    if spoofResult.decision != "PASS" {
                        self.error = spoofResult.hint ?? "Anti-spoof check failed"
                    }

                    self.isVerifying = false
                }
            } catch {
                await MainActor.run {
                    self.error = "Verification failed: \(error.localizedDescription)"
                    self.isVerifying = false
                }
            }
        }
    }
}

struct ClassificationResult {
    let passed: Bool
    let docType: String
    let side: String
    let confidence: Double
}

struct AntiSpoofResult {
    let passed: Bool
    let reason: String
    let confidence: Double
}

// MARK: - Supporting Views
struct VerificationOverlay: View {
    var body: some View {
        ZStack {
            Color.black.opacity(0.7)

            VStack(spacing: 16) {
                ProgressView()
                    .progressViewStyle(CircularProgressViewStyle(tint: .white))
                    .scaleEffect(1.5)

                Text("Verifying document...")
                    .foregroundColor(.white)
            }
        }
        .cornerRadius(12)
    }
}

struct VerificationResultsView: View {
    let classification: ClassificationResult?
    let antiSpoof: AntiSpoofResult?

    var body: some View {
        VStack(spacing: 12) {
            if let classification = classification {
                ResultRow(
                    title: "Document Classification",
                    subtitle: "\(classification.docType) - \(classification.side)",
                    passed: classification.passed,
                    confidence: classification.confidence
                )
            }

            if let antiSpoof = antiSpoof {
                ResultRow(
                    title: "Authenticity Check",
                    subtitle: antiSpoof.passed ? "Genuine document" : "Detected: \(antiSpoof.reason)",
                    passed: antiSpoof.passed,
                    confidence: antiSpoof.confidence
                )
            }
        }
    }
}

struct ResultRow: View {
    let title: String
    let subtitle: String
    let passed: Bool
    let confidence: Double

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: passed ? "checkmark.circle.fill" : "xmark.circle.fill")
                .font(.title2)
                .foregroundColor(passed ? .green : .red)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline)
                    .fontWeight(.semibold)

                Text(subtitle)
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            Spacer()

            Text("\(Int(confidence * 100))%")
                .font(.subheadline)
                .fontWeight(.bold)
                .foregroundColor(passed ? .green : .red)
        }
        .padding()
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(Color(.secondarySystemBackground))
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .stroke(passed ? Color.green : Color.red, lineWidth: 1)
                        .opacity(0.3)
                )
        )
    }
}

struct ErrorBanner: View {
    let message: String

    var body: some View {
        HStack {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundColor(.orange)

            Text(message)
                .font(.subheadline)
                .foregroundColor(.primary)
        }
        .padding()
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(Color.orange.opacity(0.1))
        )
    }
}
```

---

## Main Flow Coordinator

### VerityProSDK.swift
```swift
import SwiftUI

public struct VerityProSDK: View {
    @State private var currentStep: VerificationStep = .selectDocument
    @State private var selectedDocType: DocumentType?
    @State private var frontImage: UIImage?
    @State private var frontBurstFrames: [UIImage] = []
    @State private var backImage: UIImage?
    @State private var backBurstFrames: [UIImage] = []

    private let sessionId = UUID().uuidString

    public init() {}

    public var body: some View {
        NavigationView {
            Group {
                switch currentStep {
                case .selectDocument:
                    DocumentSelectionView(
                        selectedDocument: $selectedDocType,
                        onContinue: { currentStep = .captureFront }
                    )

                case .captureFront:
                    if let docType = selectedDocType {
                        DocumentCaptureView(
                            docType: docType,
                            side: .front,
                            sessionId: sessionId,
                            onCapture: { image, frames in
                                frontImage = image
                                frontBurstFrames = frames
                                currentStep = .previewFront
                            },
                            onBack: { currentStep = .selectDocument }
                        )
                    }

                case .previewFront:
                    if let image = frontImage, let docType = selectedDocType {
                        DocumentPreviewView(
                            image: image,
                            burstFrames: frontBurstFrames,
                            docType: docType,
                            side: .front,
                            sessionId: sessionId,
                            onContinue: {
                                if docType.requiresBackSide {
                                    currentStep = .captureBack
                                } else {
                                    currentStep = .liveness
                                }
                            },
                            onRetake: { currentStep = .captureFront }
                        )
                    }

                case .captureBack:
                    if let docType = selectedDocType {
                        DocumentCaptureView(
                            docType: docType,
                            side: .back,
                            sessionId: sessionId,
                            onCapture: { image, frames in
                                backImage = image
                                backBurstFrames = frames
                                currentStep = .previewBack
                            },
                            onBack: { currentStep = .previewFront }
                        )
                    }

                case .previewBack:
                    if let image = backImage, let docType = selectedDocType {
                        DocumentPreviewView(
                            image: image,
                            burstFrames: backBurstFrames,
                            docType: docType,
                            side: .back,
                            sessionId: sessionId,
                            onContinue: { currentStep = .liveness },
                            onRetake: { currentStep = .captureBack }
                        )
                    }

                case .liveness:
                    LivenessCheckView(
                        onComplete: { currentStep = .confirmation },
                        onBack: {
                            currentStep = selectedDocType?.requiresBackSide == true
                                ? .previewBack
                                : .previewFront
                        }
                    )

                case .confirmation:
                    ConfirmationView(
                        success: true,
                        verificationId: sessionId,
                        onDone: { resetFlow() }
                    )
                }
            }
            .navigationBarHidden(true)
        }
    }

    private func resetFlow() {
        currentStep = .selectDocument
        selectedDocType = nil
        frontImage = nil
        frontBurstFrames = []
        backImage = nil
        backBurstFrames = []
    }
}

enum VerificationStep {
    case selectDocument
    case captureFront
    case previewFront
    case captureBack
    case previewBack
    case liveness
    case confirmation
}
```

---

## Info.plist Permissions

Add these to your `Info.plist`:

```xml
<key>NSCameraUsageDescription</key>
<string>We need camera access to capture your identity document for verification.</string>

<key>NSPhotoLibraryUsageDescription</key>
<string>We need photo library access to save captured documents.</string>
```

---

## Quality Check Recommendation

**Skip client-side blur/brightness checks.** The ML backend handles quality implicitly through detection confidence. Keep the client simple.

---

## API Integration Summary

| Step | Endpoint | Frequency | Purpose |
|------|----------|-----------|---------|
| 2 (Capture) | `POST /predict` | Every 800ms | Real-time detection |
| 2.1 (Preview) | `POST /predict` | Once | Classification |
| 2.1 (Preview) | `POST /verify-burst` | Once | Anti-spoof |
| 3 (Back Capture) | `POST /predict` | Every 800ms | Real-time detection |
| 4.1 (Back Preview) | `POST /predict` | Once | Classification |
| 4.1 (Back Preview) | `POST /verify-burst` | Once | Anti-spoof |
