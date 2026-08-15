import io

p = 'veritypro-sdk/src/main/java/com/example/veritypro_sdk/ui/verification/document_capture.kt'
s = open(p, encoding='utf-8').read()

old_sharpen = '''                                        // Sharpen every frame — all of them feed the server blur gate.
                                        withContext(Dispatchers.IO) {
                                            burst.forEach { file ->
                                                try {
                                                    val bmp = BitmapFactory.decodeFile(file.path)
                                                    if (bmp != null) {
                                                        val sharpened = ImageSharpeningUtils.applySharpeningPipeline(bmp)
                                                        file.outputStream().use { out ->
                                                            sharpened.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                                                        }
                                                        if (sharpened !== bmp) bmp.recycle()
                                                    }
                                                } catch (e: Exception) {
                                                    Log.w("DocumentCapture", "Auto-capture: sharpening failed for ${file.name}: ${e.message}")
                                                }
                                            }
                                        }'''
new_sharpen = '''                                        // LATENCY FIX (2026-08-15, measured 10.3s client post-
                                        // processing): sharpen ONLY the primary frame — it is the
                                        // one shown in preview and uploaded as the document (OCR
                                        // benefits). The forensic burst frames are raw-sharp
                                        // (Laplacian ~1000 on-device) and artificial sharpening
                                        // artifacts can feed tamper/spoof false positives.
                                        withContext(Dispatchers.IO) {
                                            val file = burst.first()
                                            try {
                                                val bmp = BitmapFactory.decodeFile(file.path)
                                                if (bmp != null) {
                                                    val sharpened = ImageSharpeningUtils.applySharpeningPipeline(bmp)
                                                    file.outputStream().use { out ->
                                                        sharpened.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                                                    }
                                                    if (sharpened !== bmp) bmp.recycle()
                                                }
                                            } catch (e: Exception) {
                                                Log.w("DocumentCapture", "Auto-capture: sharpening failed for ${file.name}: ${e.message}")
                                            }
                                        }'''
assert s.count(old_sharpen) == 1, ("auto sharpen", s.count(old_sharpen))
s = s.replace(old_sharpen, new_sharpen)

old_manual = old_sharpen.replace('Auto-capture: sharpening failed', 'Manual capture: sharpening failed').replace('                                        ', '                                ')
new_manual = new_sharpen.replace('Auto-capture: sharpening failed', 'Manual capture: sharpening failed').replace('                                        ', '                                ')
assert s.count(old_manual) == 1, ("manual sharpen", s.count(old_manual))
s = s.replace(old_manual, new_manual)

old_gate = '''                                        val presenceOk = if (isBackSide) true else try {
                                            // Decode downsampled — the bitmap overload re-encodes at
                                            // 640px/q65 (~100-200KB) vs the raw file's 1-3MB. A 40ms
                                            // presence check must not cost a full-res upload.
                                            val gateOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
                                            val gateBmp = android.graphics.BitmapFactory.decodeFile(primaryFile.path, gateOpts)
                                            if (gateBmp != null) {
                                                try {
                                                    val gate = MLRepository().predict(
                                                        sessionId = kycSessionId.ifBlank { "presence-gate" },
                                                        bitmap = gateBmp,
                                                        docTypeExpected = MLDocumentType.fromSdkType(documentType ?: 1),
                                                        sideExpected = if (capturedFiles.isNotEmpty()) "BACK" else "FRONT"
                                                    )
                                                    if (gate is Resource.Success) gate.data.docOk else true
                                                } finally {
                                                    gateBmp.recycle()
                                                }
                                            } else true
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            Log.w("DocumentCapture", "Presence gate errored (${e.message}) — proceeding to verify-burst")
                                            true
                                        }'''
new_gate = '''                                        // LATENCY FIX: local geometry detector on the captured
                                        // frame replaces the server /predict round-trip (~1.5s).
                                        // Same fail-open semantics; verify-burst stays authority.
                                        val presenceOk = if (isBackSide) true else try {
                                            val gateOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                                            val gateBmp = android.graphics.BitmapFactory.decodeFile(primaryFile.path, gateOpts)
                                            if (gateBmp != null) {
                                                try {
                                                    DocumentFrameDetector.analyse(gateBmp).present
                                                } finally {
                                                    gateBmp.recycle()
                                                }
                                            } else true
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            Log.w("DocumentCapture", "Presence gate errored (${e.message}) — proceeding to verify-burst")
                                            true
                                        }'''
assert s.count(old_gate) == 1, "auto gate not found"
s = s.replace(old_gate, new_gate)

old_gate_m = old_gate.replace('                                        ', '                                ')
new_gate_m = new_gate.replace('                                        ', '                                ')
assert s.count(old_gate_m) == 1, "manual gate not found"
s = s.replace(old_gate_m, new_gate_m)

open(p, 'w', encoding='utf-8', newline='\n').write(s)
print("document_capture.kt patched (both paths)")

p2 = 'veritypro-sdk/src/main/java/com/example/veritypro_sdk/services/ml_repository.kt'
s2 = open(p2, encoding='utf-8').read()
assert 'MAX_BURST_FRAME_DIMENSION = 1280' in s2
s2 = s2.replace('private const val MAX_BURST_FRAME_DIMENSION = 1280',
    '''// 2026-08-15 latency fix: server analyses at 768px (LLM_ANALYSIS_IMAGE_SIZE)
        // — 896px keeps headroom above that while halving the upload payload
        // (~2MB -> ~0.8MB; measured ~10s of a 25s verdict was uplink time).
        private const val MAX_BURST_FRAME_DIMENSION = 896''')
s2 = s2.replace('private const val JPEG_QUALITY_BURST = 85', 'private const val JPEG_QUALITY_BURST = 80')
open(p2, 'w', encoding='utf-8', newline='\n').write(s2)
print("ml_repository.kt patched (896px/q80)")
