# v1.2.0 Capture-Quality Release — Physical Device Test Checklist

**Release gate:** every item below must pass on at least 2 physical devices
(one mid-range, one foldable — the defect class was found on a Galaxy Z Fold3)
before `./gradlew publish` and before any host app adopts 1.2.0.

## What changed (why these tests)

| Change | Root cause it fixes |
|---|---|
| Scene-adaptive exposure (was blanket +1.0 EV) | Session 76bc252d: blown-out white captures in normal light, high-ISO grain in low light |
| Post-capture quality gate on the FINAL JPEG | Same session: unreadable capture uploaded → false "face mismatch" hard-decline |
| Back-slot portrait gate | Session e404a002: front captured in the back slot → COH_007 abort server-side |
| EXIF rotation baked into pixels | 90°-rotated uploads (Fold-class devices worst case) |
| Fail-closed on ML API unavailability | Anti-spoof gate could be defeated by blocking the endpoint |

## Test matrix

### T1 — Exposure (adaptive EV)
- [ ] Bright daylight / near a window: capture front+back — card must NOT be washed out; logcat shows `EV=0.0 … scene median=…`
- [ ] Normal indoor light: capture — correct exposure, `EV=0.0`
- [ ] Dim room (lights low): capture — logcat shows `EV=0.5` or `EV=1.0`; image readable, no excessive grain
- [ ] Point at a lamp/white surface: logcat shows EV drop to `0.0` (clip guard)

### T2 — Post-capture gate
- [ ] Capture in near-darkness → rejected with "Too dark — move to a brighter area and retake."
- [ ] Capture while moving the phone (motion blur) → rejected with "The photo is blurry…"
- [ ] Cover the portrait with a finger (front side) → rejected with "The photo on your document is not readable…"
- [ ] Clean, well-lit capture → passes with no added friction (verify no false rejects across ≥10 captures)

### T3 — Back-slot gate (e404a002 class)
- [ ] In the BACK capture step, present the FRONT of the licence → rejected with "This looks like the front of your document…"
- [ ] Present the genuine back → passes (ghost images / holograms must NOT trigger the gate)

### T4 — Rotation
- [ ] Capture with the phone/document sideways; verify the UPLOADED image (staging dashboard) is upright
- [ ] Foldable: capture folded AND unfolded; uploaded images upright in both postures

### T5 — Fail-closed
- [ ] Airplane-mode mid-flow (after camera opens): capture attempt → "Verification service unavailable" retry message, NOT a silent pass

### T6 — End-to-end regression
- [ ] Full happy-path KYC (front, back, selfie, liveness) completes and reaches Approved on staging
- [ ] Session appears correctly in the operator dashboard with readable document images

**Sign-off:** QA lead + one engineer, recorded in the PR that bumps the host app.
