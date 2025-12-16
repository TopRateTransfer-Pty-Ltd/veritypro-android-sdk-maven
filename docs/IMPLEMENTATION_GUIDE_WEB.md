# VerityPro Web SDK - Implementation Guide

## Backend Configuration
```
BASE_URL: http://localhost:8001
```

## API Endpoints Used
| Endpoint | Purpose |
|----------|---------|
| `POST /v1/kyc/doc/predict` | Single frame detection + classification |
| `POST /v1/kyc/doc/verify-burst` | Multi-frame anti-spoof verification |

---

## Complete Flow Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         VERITYPRO WEB KYC FLOW                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐    ┌───────────┐ │
│  │   STEP 1     │    │   STEP 2     │    │   STEP 2.1   │    │  STEP 3   │ │
│  │  Doc Select  │───▶│ Front Capture│───▶│Front Preview │───▶│Back Capture│ │
│  │              │    │  + Detection │    │ + Classify   │    │+ Detection│ │
│  └──────────────┘    └──────────────┘    │ + AntiSpoof  │    └───────────┘ │
│                                          └──────────────┘          │        │
│                                                                    ▼        │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐    ┌───────────┐ │
│  │   STEP 6     │    │   STEP 5     │    │   STEP 4.1   │    │           │ │
│  │ Confirmation │◀───│   Liveness   │◀───│ Back Preview │◀───│           │ │
│  │              │    │    Check     │    │ + Classify   │    │           │ │
│  └──────────────┘    └──────────────┘    │ + AntiSpoof  │    └───────────┘ │
│                                          └──────────────┘                   │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Step 1: Document Selection

### UI Component: `DocumentSelectionScreen.tsx`

```tsx
import React, { useState } from 'react';

interface DocumentType {
  id: 'PASSPORT' | 'DRIVERS_LICENSE' | 'ID_CARD';
  label: string;
  icon: string;
  requiresBack: boolean;
}

const DOCUMENT_TYPES: DocumentType[] = [
  { id: 'PASSPORT', label: 'Passport', icon: '🛂', requiresBack: false },
  { id: 'DRIVERS_LICENSE', label: "Driver's License", icon: '🚗', requiresBack: true },
  { id: 'ID_CARD', label: 'National ID Card', icon: '🪪', requiresBack: true },
];

interface Props {
  onSelect: (docType: DocumentType) => void;
}

export const DocumentSelectionScreen: React.FC<Props> = ({ onSelect }) => {
  const [selected, setSelected] = useState<DocumentType | null>(null);

  return (
    <div className="doc-selection-container">
      <h1>Select Your Document</h1>
      <p>Choose the type of identity document you want to verify</p>

      <div className="doc-grid">
        {DOCUMENT_TYPES.map((doc) => (
          <button
            key={doc.id}
            className={`doc-card ${selected?.id === doc.id ? 'selected' : ''}`}
            onClick={() => setSelected(doc)}
          >
            <span className="doc-icon">{doc.icon}</span>
            <span className="doc-label">{doc.label}</span>
            {doc.requiresBack && (
              <span className="badge">Front + Back</span>
            )}
          </button>
        ))}
      </div>

      <button
        className="btn-primary"
        disabled={!selected}
        onClick={() => selected && onSelect(selected)}
      >
        Continue
      </button>
    </div>
  );
};
```

### CSS Styles
```css
.doc-selection-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 24px;
  max-width: 600px;
  margin: 0 auto;
}

.doc-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 16px;
  width: 100%;
  margin: 24px 0;
}

.doc-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 24px;
  border: 2px solid #e0e0e0;
  border-radius: 12px;
  background: white;
  cursor: pointer;
  transition: all 0.2s;
}

.doc-card:hover {
  border-color: #2196F3;
  transform: translateY(-2px);
}

.doc-card.selected {
  border-color: #2196F3;
  background: #E3F2FD;
}

.doc-icon {
  font-size: 48px;
  margin-bottom: 12px;
}

.badge {
  font-size: 11px;
  background: #FFF3E0;
  color: #E65100;
  padding: 4px 8px;
  border-radius: 4px;
  margin-top: 8px;
}

.btn-primary {
  width: 100%;
  padding: 16px;
  background: #2196F3;
  color: white;
  border: none;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
}

.btn-primary:disabled {
  background: #BDBDBD;
  cursor: not-allowed;
}
```

---

## Step 2: Front Document Capture

### ML Detection Integration (Real-time)

**Recommendation:** Call `/predict` endpoint every 500-1000ms during camera preview for real-time feedback. This gives user guidance without overwhelming the server.

### UI Component: `IdFrontScreen.tsx`

```tsx
import React, { useRef, useState, useEffect, useCallback } from 'react';

interface DetectionResult {
  docOk: boolean;
  bbox: { x: number; y: number; w: number; h: number } | null;
  hint: string;
  confidence: { detection: number };
}

interface Props {
  docType: 'PASSPORT' | 'DRIVERS_LICENSE' | 'ID_CARD';
  onCapture: (imageBlob: Blob, detectionResult: DetectionResult) => void;
  onBack: () => void;
}

export const IdFrontScreen: React.FC<Props> = ({ docType, onCapture, onBack }) => {
  const videoRef = useRef<HTMLVideoElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const streamRef = useRef<MediaStream | null>(null);

  const [isReady, setIsReady] = useState(false);
  const [detection, setDetection] = useState<DetectionResult | null>(null);
  const [isCapturing, setIsCapturing] = useState(false);
  const sessionId = useRef(crypto.randomUUID());

  // Initialize camera
  useEffect(() => {
    const initCamera = async () => {
      try {
        const stream = await navigator.mediaDevices.getUserMedia({
          video: {
            facingMode: 'environment', // Rear camera
            width: { ideal: 1920 },
            height: { ideal: 1080 },
          },
        });

        if (videoRef.current) {
          videoRef.current.srcObject = stream;
          streamRef.current = stream;
          setIsReady(true);
        }
      } catch (err) {
        console.error('Camera access failed:', err);
      }
    };

    initCamera();

    return () => {
      streamRef.current?.getTracks().forEach(track => track.stop());
    };
  }, []);

  // Real-time detection (every 800ms)
  useEffect(() => {
    if (!isReady) return;

    const detectDocument = async () => {
      if (!videoRef.current || !canvasRef.current) return;

      const canvas = canvasRef.current;
      const video = videoRef.current;
      const ctx = canvas.getContext('2d');
      if (!ctx) return;

      canvas.width = video.videoWidth;
      canvas.height = video.videoHeight;
      ctx.drawImage(video, 0, 0);

      // Convert to base64
      const base64 = canvas.toDataURL('image/jpeg', 0.8).split(',')[1];

      try {
        const response = await fetch('http://localhost:8001/v1/kyc/doc/predict', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            sessionId: sessionId.current,
            docTypeExpected: docType,
            sideExpected: 'FRONT',
            imageJpegBase64: base64,
          }),
        });

        const result = await response.json();
        setDetection(result);
      } catch (err) {
        console.error('Detection failed:', err);
      }
    };

    const interval = setInterval(detectDocument, 800);
    return () => clearInterval(interval);
  }, [isReady, docType]);

  // Capture photo
  const handleCapture = useCallback(async () => {
    if (!videoRef.current || !canvasRef.current || !detection?.docOk) return;

    setIsCapturing(true);

    const canvas = canvasRef.current;
    const video = videoRef.current;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    canvas.width = video.videoWidth;
    canvas.height = video.videoHeight;
    ctx.drawImage(video, 0, 0);

    canvas.toBlob(
      (blob) => {
        if (blob) {
          onCapture(blob, detection);
        }
        setIsCapturing(false);
      },
      'image/jpeg',
      0.95
    );
  }, [detection, onCapture]);

  return (
    <div className="capture-container">
      {/* Header */}
      <div className="capture-header">
        <button className="btn-back" onClick={onBack}>←</button>
        <h2>Front of {docType.replace('_', ' ')}</h2>
      </div>

      {/* Camera View */}
      <div className="camera-wrapper">
        <video
          ref={videoRef}
          autoPlay
          playsInline
          muted
          className="camera-feed"
        />

        {/* Document Frame Overlay */}
        <div className={`doc-frame ${detection?.docOk ? 'detected' : ''}`}>
          <div className="corner top-left" />
          <div className="corner top-right" />
          <div className="corner bottom-left" />
          <div className="corner bottom-right" />
        </div>

        {/* Detection Hint */}
        <div className={`hint-banner ${detection?.docOk ? 'success' : 'warning'}`}>
          {detection?.hint || 'Position document within frame'}
        </div>
      </div>

      {/* Hidden canvas for capture */}
      <canvas ref={canvasRef} style={{ display: 'none' }} />

      {/* Capture Button */}
      <div className="capture-controls">
        <button
          className={`btn-capture ${detection?.docOk ? 'ready' : 'disabled'}`}
          onClick={handleCapture}
          disabled={!detection?.docOk || isCapturing}
        >
          {isCapturing ? 'Capturing...' : 'CAPTURE'}
        </button>

        {/* Confidence indicator */}
        {detection && (
          <div className="confidence-bar">
            <div
              className="confidence-fill"
              style={{ width: `${(detection.confidence?.detection || 0) * 100}%` }}
            />
            <span>{Math.round((detection.confidence?.detection || 0) * 100)}%</span>
          </div>
        )}
      </div>
    </div>
  );
};
```

### CSS Styles
```css
.capture-container {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: #000;
}

.capture-header {
  display: flex;
  align-items: center;
  padding: 16px;
  background: rgba(0, 0, 0, 0.8);
  color: white;
}

.btn-back {
  background: none;
  border: none;
  color: white;
  font-size: 24px;
  padding: 8px;
  cursor: pointer;
}

.camera-wrapper {
  flex: 1;
  position: relative;
  overflow: hidden;
}

.camera-feed {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.doc-frame {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  width: 85%;
  aspect-ratio: 1.586; /* ID card ratio */
  border: 3px solid rgba(255, 255, 255, 0.5);
  border-radius: 12px;
  transition: border-color 0.3s;
}

.doc-frame.detected {
  border-color: #4CAF50;
  box-shadow: 0 0 20px rgba(76, 175, 80, 0.5);
}

.corner {
  position: absolute;
  width: 30px;
  height: 30px;
  border: 4px solid white;
}

.corner.top-left {
  top: -2px;
  left: -2px;
  border-right: none;
  border-bottom: none;
  border-radius: 8px 0 0 0;
}

.corner.top-right {
  top: -2px;
  right: -2px;
  border-left: none;
  border-bottom: none;
  border-radius: 0 8px 0 0;
}

.corner.bottom-left {
  bottom: -2px;
  left: -2px;
  border-right: none;
  border-top: none;
  border-radius: 0 0 0 8px;
}

.corner.bottom-right {
  bottom: -2px;
  right: -2px;
  border-left: none;
  border-top: none;
  border-radius: 0 0 8px 0;
}

.doc-frame.detected .corner {
  border-color: #4CAF50;
}

.hint-banner {
  position: absolute;
  bottom: 20px;
  left: 50%;
  transform: translateX(-50%);
  padding: 12px 24px;
  border-radius: 24px;
  font-size: 14px;
  font-weight: 500;
}

.hint-banner.warning {
  background: rgba(255, 152, 0, 0.9);
  color: white;
}

.hint-banner.success {
  background: rgba(76, 175, 80, 0.9);
  color: white;
}

.capture-controls {
  padding: 24px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  background: rgba(0, 0, 0, 0.8);
}

.btn-capture {
  width: 80px;
  height: 80px;
  border-radius: 50%;
  border: 4px solid white;
  background: transparent;
  color: white;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-capture.ready {
  background: #4CAF50;
  border-color: #4CAF50;
}

.btn-capture.disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.confidence-bar {
  width: 200px;
  height: 8px;
  background: rgba(255, 255, 255, 0.2);
  border-radius: 4px;
  position: relative;
  overflow: hidden;
}

.confidence-fill {
  height: 100%;
  background: #4CAF50;
  transition: width 0.3s;
}

.confidence-bar span {
  position: absolute;
  right: -40px;
  top: -4px;
  color: white;
  font-size: 12px;
}
```

---

## Step 2.1: Front Preview Screen

### ML Classification + Anti-Spoof Verification

**Flow:**
1. Display captured image
2. Call `/predict` for classification confirmation
3. Collect 6-10 burst frames during preview
4. Call `/verify-burst` for anti-spoof check
5. Show results and allow Continue or Retake

### UI Component: `IdPreviewScreen.tsx`

```tsx
import React, { useState, useEffect, useCallback } from 'react';

interface VerificationResult {
  classification: {
    docOk: boolean;
    docType: string;
    side: string;
    confidence: number;
  };
  antiSpoof: {
    decision: 'PASS' | 'RETRY';
    score: number;
    reason: string;
  };
}

interface Props {
  imageBlob: Blob;
  docType: 'PASSPORT' | 'DRIVERS_LICENSE' | 'ID_CARD';
  side: 'FRONT' | 'BACK';
  sessionId: string;
  onContinue: () => void;
  onRetake: () => void;
}

export const IdPreviewScreen: React.FC<Props> = ({
  imageBlob,
  docType,
  side,
  sessionId,
  onContinue,
  onRetake,
}) => {
  const [imageUrl, setImageUrl] = useState<string>('');
  const [verification, setVerification] = useState<VerificationResult | null>(null);
  const [isVerifying, setIsVerifying] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Create blob URL for display
  useEffect(() => {
    const url = URL.createObjectURL(imageBlob);
    setImageUrl(url);
    return () => URL.revokeObjectURL(url);
  }, [imageBlob]);

  // Run verification pipeline
  useEffect(() => {
    const verify = async () => {
      setIsVerifying(true);
      setError(null);

      try {
        // Convert blob to base64
        const base64 = await blobToBase64(imageBlob);

        // Step 1: Classification check
        const classifyResponse = await fetch('http://localhost:8001/v1/kyc/doc/predict', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            sessionId,
            docTypeExpected: docType,
            sideExpected: side,
            imageJpegBase64: base64,
          }),
        });
        const classifyResult = await classifyResponse.json();

        if (!classifyResult.docOk) {
          setError(classifyResult.hint || 'Document validation failed');
          setIsVerifying(false);
          return;
        }

        // Step 2: Anti-spoof verification (using single frame as burst)
        // In production, collect 6-10 frames during camera preview
        const burstFrames = [base64]; // Simplified - use collected frames in production

        const spoofResponse = await fetch('http://localhost:8001/v1/kyc/doc/verify-burst', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            sessionId,
            frames: burstFrames,
            docTypeExpected: docType,
            sideExpected: side,
          }),
        });
        const spoofResult = await spoofResponse.json();

        setVerification({
          classification: {
            docOk: classifyResult.docOk,
            docType: classifyResult.docType,
            side: classifyResult.side,
            confidence: classifyResult.confidence?.classification || 0,
          },
          antiSpoof: {
            decision: spoofResult.decision,
            score: spoofResult.spoof?.score || 0,
            reason: spoofResult.spoof?.reason || 'UNKNOWN',
          },
        });

        if (spoofResult.decision !== 'PASS') {
          setError(spoofResult.hint || 'Anti-spoof check failed');
        }
      } catch (err) {
        setError('Verification failed. Please try again.');
        console.error('Verification error:', err);
      } finally {
        setIsVerifying(false);
      }
    };

    verify();
  }, [imageBlob, docType, side, sessionId]);

  const isVerified = verification &&
    verification.classification.docOk &&
    verification.antiSpoof.decision === 'PASS';

  return (
    <div className="preview-container">
      {/* Header */}
      <div className="preview-header">
        <h2>{side === 'FRONT' ? 'Front' : 'Back'} of Document</h2>
        <p>Review your captured image</p>
      </div>

      {/* Image Preview */}
      <div className="preview-image-wrapper">
        <img src={imageUrl} alt="Captured document" className="preview-image" />

        {/* Verification Status Overlay */}
        {isVerifying && (
          <div className="verification-overlay">
            <div className="spinner" />
            <span>Verifying document...</span>
          </div>
        )}
      </div>

      {/* Verification Results */}
      {!isVerifying && verification && (
        <div className="verification-results">
          {/* Classification Result */}
          <div className={`result-item ${verification.classification.docOk ? 'pass' : 'fail'}`}>
            <span className="result-icon">
              {verification.classification.docOk ? '✓' : '✗'}
            </span>
            <div className="result-details">
              <span className="result-label">Document Classification</span>
              <span className="result-value">
                {verification.classification.docType} - {verification.classification.side}
              </span>
            </div>
            <span className="result-confidence">
              {Math.round(verification.classification.confidence * 100)}%
            </span>
          </div>

          {/* Anti-Spoof Result */}
          <div className={`result-item ${verification.antiSpoof.decision === 'PASS' ? 'pass' : 'fail'}`}>
            <span className="result-icon">
              {verification.antiSpoof.decision === 'PASS' ? '✓' : '✗'}
            </span>
            <div className="result-details">
              <span className="result-label">Authenticity Check</span>
              <span className="result-value">
                {verification.antiSpoof.reason === 'PASS'
                  ? 'Genuine document'
                  : `Detected: ${verification.antiSpoof.reason}`}
              </span>
            </div>
            <span className="result-confidence">
              {Math.round((1 - verification.antiSpoof.score) * 100)}%
            </span>
          </div>
        </div>
      )}

      {/* Error Message */}
      {error && (
        <div className="error-banner">
          <span className="error-icon">⚠️</span>
          <span>{error}</span>
        </div>
      )}

      {/* Action Buttons */}
      <div className="preview-actions">
        <button className="btn-secondary" onClick={onRetake}>
          Retake Photo
        </button>
        <button
          className="btn-primary"
          onClick={onContinue}
          disabled={!isVerified || isVerifying}
        >
          {isVerifying ? 'Verifying...' : isVerified ? 'Continue' : 'Retry Required'}
        </button>
      </div>
    </div>
  );
};

// Helper function
async function blobToBase64(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onloadend = () => {
      const result = reader.result as string;
      resolve(result.split(',')[1]);
    };
    reader.onerror = reject;
    reader.readAsDataURL(blob);
  });
}
```

### CSS Styles
```css
.preview-container {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  background: #f5f5f5;
}

.preview-header {
  padding: 24px;
  text-align: center;
  background: white;
}

.preview-header h2 {
  margin: 0 0 8px 0;
  color: #212121;
}

.preview-header p {
  margin: 0;
  color: #757575;
}

.preview-image-wrapper {
  position: relative;
  margin: 16px;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}

.preview-image {
  width: 100%;
  display: block;
}

.verification-overlay {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.7);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: white;
  gap: 16px;
}

.spinner {
  width: 48px;
  height: 48px;
  border: 4px solid rgba(255, 255, 255, 0.3);
  border-top-color: white;
  border-radius: 50%;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.verification-results {
  padding: 0 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.result-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
  background: white;
  border-radius: 12px;
  border-left: 4px solid;
}

.result-item.pass {
  border-left-color: #4CAF50;
}

.result-item.fail {
  border-left-color: #F44336;
}

.result-icon {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  font-size: 18px;
}

.result-item.pass .result-icon {
  background: #E8F5E9;
  color: #4CAF50;
}

.result-item.fail .result-icon {
  background: #FFEBEE;
  color: #F44336;
}

.result-details {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.result-label {
  font-weight: 600;
  color: #212121;
}

.result-value {
  font-size: 13px;
  color: #757575;
}

.result-confidence {
  font-weight: 600;
  color: #4CAF50;
}

.error-banner {
  margin: 16px;
  padding: 16px;
  background: #FFEBEE;
  border-radius: 12px;
  display: flex;
  align-items: center;
  gap: 12px;
  color: #C62828;
}

.preview-actions {
  margin-top: auto;
  padding: 24px;
  display: flex;
  gap: 12px;
  background: white;
  border-top: 1px solid #e0e0e0;
}

.btn-secondary {
  flex: 1;
  padding: 16px;
  background: white;
  border: 2px solid #e0e0e0;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 600;
  color: #757575;
  cursor: pointer;
}

.btn-primary {
  flex: 2;
  padding: 16px;
  background: #2196F3;
  border: none;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 600;
  color: white;
  cursor: pointer;
}

.btn-primary:disabled {
  background: #BDBDBD;
  cursor: not-allowed;
}
```

---

## Steps 3 & 4.1: Back Document Capture & Preview

**Same components as Steps 2 & 2.1** with `side: 'BACK'` parameter.

```tsx
// Usage for back capture
<IdFrontScreen
  docType={docType}
  side="BACK"  // Changed from FRONT
  onCapture={handleBackCapture}
  onBack={goToFrontPreview}
/>

// Usage for back preview
<IdPreviewScreen
  imageBlob={backImageBlob}
  docType={docType}
  side="BACK"  // Changed from FRONT
  sessionId={sessionId}
  onContinue={goToLiveness}
  onRetake={retakeBackPhoto}
/>
```

---

## Quality Check Recommendation

**My recommendation:** Skip client-side quality checks (blur/brightness). Here's why:

| Approach | Pros | Cons |
|----------|------|------|
| Client-side quality | Fast feedback | Extra complexity, inconsistent, users can bypass |
| Server-side only | Consistent, harder to bypass, simpler client | Slight latency |

The ML backend already handles quality implicitly through detection confidence. Low-quality images won't detect well. **Keep it simple.**

---

## Step 5: Liveness Check

### UI Component: `LivenessCheckScreen.tsx`

```tsx
import React, { useRef, useState, useEffect } from 'react';

type Challenge = 'BLINK' | 'TURN_LEFT' | 'TURN_RIGHT' | 'SMILE';

interface Props {
  onComplete: (livenessResult: { passed: boolean; score: number }) => void;
  onBack: () => void;
}

export const LivenessCheckScreen: React.FC<Props> = ({ onComplete, onBack }) => {
  const videoRef = useRef<HTMLVideoElement>(null);
  const [currentChallenge, setCurrentChallenge] = useState<Challenge>('BLINK');
  const [progress, setProgress] = useState(0);
  const [instruction, setInstruction] = useState('Position your face in the circle');

  const challenges: { type: Challenge; instruction: string }[] = [
    { type: 'BLINK', instruction: 'Blink your eyes naturally' },
    { type: 'TURN_LEFT', instruction: 'Slowly turn your head left' },
    { type: 'TURN_RIGHT', instruction: 'Slowly turn your head right' },
    { type: 'SMILE', instruction: 'Give us a smile!' },
  ];

  // Camera initialization and face detection logic here...

  return (
    <div className="liveness-container">
      <div className="liveness-header">
        <h2>Liveness Check</h2>
        <p>Follow the instructions below</p>
      </div>

      <div className="camera-circle">
        <video ref={videoRef} autoPlay playsInline muted />
        <div className="face-guide" />
      </div>

      <div className="challenge-instruction">
        <span className="challenge-icon">👁️</span>
        <span>{instruction}</span>
      </div>

      <div className="progress-bar">
        {challenges.map((c, i) => (
          <div
            key={c.type}
            className={`progress-step ${i <= progress ? 'completed' : ''}`}
          />
        ))}
      </div>
    </div>
  );
};
```

---

## Step 6: Confirmation Screen

```tsx
import React from 'react';

interface Props {
  result: {
    success: boolean;
    documentType: string;
    verificationId: string;
  };
  onDone: () => void;
}

export const ConfirmationScreen: React.FC<Props> = ({ result, onDone }) => {
  return (
    <div className="confirmation-container">
      <div className={`status-icon ${result.success ? 'success' : 'failed'}`}>
        {result.success ? '✓' : '✗'}
      </div>

      <h1>{result.success ? 'Verification Complete!' : 'Verification Failed'}</h1>

      <p>
        {result.success
          ? 'Your identity has been successfully verified.'
          : 'We could not verify your identity. Please try again.'}
      </p>

      {result.success && (
        <div className="verification-id">
          <span>Verification ID:</span>
          <code>{result.verificationId}</code>
        </div>
      )}

      <button className="btn-primary" onClick={onDone}>
        {result.success ? 'Continue' : 'Try Again'}
      </button>
    </div>
  );
};
```

---

## Main App Flow Controller

```tsx
import React, { useState } from 'react';
import { DocumentSelectionScreen } from './DocumentSelectionScreen';
import { IdFrontScreen } from './IdFrontScreen';
import { IdPreviewScreen } from './IdPreviewScreen';
import { LivenessCheckScreen } from './LivenessCheckScreen';
import { ConfirmationScreen } from './ConfirmationScreen';

type Step =
  | 'SELECT_DOC'
  | 'CAPTURE_FRONT'
  | 'PREVIEW_FRONT'
  | 'CAPTURE_BACK'
  | 'PREVIEW_BACK'
  | 'LIVENESS'
  | 'CONFIRMATION';

interface DocumentType {
  id: 'PASSPORT' | 'DRIVERS_LICENSE' | 'ID_CARD';
  requiresBack: boolean;
}

export const VerityProKYC: React.FC = () => {
  const [step, setStep] = useState<Step>('SELECT_DOC');
  const [docType, setDocType] = useState<DocumentType | null>(null);
  const [frontImage, setFrontImage] = useState<Blob | null>(null);
  const [backImage, setBackImage] = useState<Blob | null>(null);
  const [sessionId] = useState(() => crypto.randomUUID());

  const handleDocSelect = (doc: DocumentType) => {
    setDocType(doc);
    setStep('CAPTURE_FRONT');
  };

  const handleFrontCapture = (blob: Blob) => {
    setFrontImage(blob);
    setStep('PREVIEW_FRONT');
  };

  const handleFrontPreviewContinue = () => {
    if (docType?.requiresBack) {
      setStep('CAPTURE_BACK');
    } else {
      setStep('LIVENESS');
    }
  };

  const handleBackCapture = (blob: Blob) => {
    setBackImage(blob);
    setStep('PREVIEW_BACK');
  };

  const handleBackPreviewContinue = () => {
    setStep('LIVENESS');
  };

  const handleLivenessComplete = () => {
    setStep('CONFIRMATION');
  };

  return (
    <div className="veritypro-container">
      {step === 'SELECT_DOC' && (
        <DocumentSelectionScreen onSelect={handleDocSelect} />
      )}

      {step === 'CAPTURE_FRONT' && docType && (
        <IdFrontScreen
          docType={docType.id}
          onCapture={handleFrontCapture}
          onBack={() => setStep('SELECT_DOC')}
        />
      )}

      {step === 'PREVIEW_FRONT' && frontImage && docType && (
        <IdPreviewScreen
          imageBlob={frontImage}
          docType={docType.id}
          side="FRONT"
          sessionId={sessionId}
          onContinue={handleFrontPreviewContinue}
          onRetake={() => setStep('CAPTURE_FRONT')}
        />
      )}

      {step === 'CAPTURE_BACK' && docType && (
        <IdFrontScreen
          docType={docType.id}
          onCapture={handleBackCapture}
          onBack={() => setStep('PREVIEW_FRONT')}
        />
      )}

      {step === 'PREVIEW_BACK' && backImage && docType && (
        <IdPreviewScreen
          imageBlob={backImage}
          docType={docType.id}
          side="BACK"
          sessionId={sessionId}
          onContinue={handleBackPreviewContinue}
          onRetake={() => setStep('CAPTURE_BACK')}
        />
      )}

      {step === 'LIVENESS' && (
        <LivenessCheckScreen
          onComplete={handleLivenessComplete}
          onBack={() => setStep(docType?.requiresBack ? 'PREVIEW_BACK' : 'PREVIEW_FRONT')}
        />
      )}

      {step === 'CONFIRMATION' && (
        <ConfirmationScreen
          result={{ success: true, documentType: docType?.id || '', verificationId: sessionId }}
          onDone={() => window.location.reload()}
        />
      )}
    </div>
  );
};
```

---

## API Integration Summary

| Step | Endpoint | When Called | Purpose |
|------|----------|-------------|---------|
| 2 (Front Capture) | `POST /predict` | Every 800ms during preview | Real-time document detection |
| 2.1 (Front Preview) | `POST /predict` | Once on preview load | Classification confirmation |
| 2.1 (Front Preview) | `POST /verify-burst` | After classification passes | Anti-spoof verification |
| 3 (Back Capture) | `POST /predict` | Every 800ms during preview | Real-time document detection |
| 4.1 (Back Preview) | `POST /predict` | Once on preview load | Classification confirmation |
| 4.1 (Back Preview) | `POST /verify-burst` | After classification passes | Anti-spoof verification |

---

## Recommended: Burst Frame Collection

For better anti-spoof detection, collect frames during the camera preview:

```tsx
// In capture screen, collect frames for burst verification
const framesRef = useRef<string[]>([]);

useEffect(() => {
  if (!isReady) return;

  const collectFrame = async () => {
    // ... get base64 frame
    framesRef.current.push(base64);

    // Keep only last 10 frames
    if (framesRef.current.length > 10) {
      framesRef.current.shift();
    }
  };

  const interval = setInterval(collectFrame, 200); // 5 FPS
  return () => clearInterval(interval);
}, [isReady]);

// Pass frames to preview screen
const handleCapture = () => {
  onCapture(blob, { burstFrames: framesRef.current });
};
```

Then in preview screen, use collected frames for `/verify-burst`:

```tsx
const spoofResponse = await fetch('/v1/kyc/doc/verify-burst', {
  method: 'POST',
  body: JSON.stringify({
    sessionId,
    frames: burstFrames, // 6-10 frames from capture
    docTypeExpected: docType,
    sideExpected: side,
  }),
});
```
