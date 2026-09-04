# Native SDK brand customization — implementation specification

## 1. Panel verdicts

| Role | Verdict | Key condition |
|---|---|---|
| Tech Boss | APPROVED-WITH-CONDITIONS | Use one immutable nested VpBrandConfig; override tokens only at native roots. |
| Product Owner | APPROVED | Optional setting; no effect on decisions. |
| Business Analyst | APPROVED | Strict hex/HTTPS validation across all bridges. |
| Tech Lead | APPROVED-WITH-CONDITIONS | Wire actual theme roots, not only the requested stage router/prototype. |
| AMLCO | APPROVED-WITH-CONDITIONS | Preserve regulatory-state colours; redact logo diagnostics. |
| Scrum Master | SCOPED | Client-side-only, with all builds/tests required. |

No BLOCKED verdict and no unanswered implementation question.

## 2. Evidence and delta analysis

### Local audit

| Area | State | Evidence / gap |
|---|---|---|
| iOS models/VerityOption.swift | Complete | Public initializer and payload model have no brand field; payload must remain UI-only. |
| iOS theme/colors.swift | Partial / legacy | ThemeColors.primary uses blue/accent; buttonBackground uses VerityFlowColors.ctaBlue. |
| iOS theme/VerityTheme.swift | Absent | Active redesign theme is redesign/VerityTheme.swift, not the requested path. |
| iOS utils/verity_pro.swift | Complete | VerityProSDK(options:themeMode:) chooses redesign/legacy/server-driven roots; it never injects a brand. |
| iOS redesign | Complete but fixed | VerityVerificationView calls .verityTheme(); VerityColors has fixed brand/progress/liveness tokens. Button/progress consume tokens. VerityWelcomeScreen has no logo. |
| iOS legacy intro/loading | Partial | IntroView has VERITYPRO text and direct ctaBlue; loading dots direct darkBlue. Shared/address/EDD/flow/permission views have further primary hard-codes. |
| Android verity_options.kt | Complete | Parcelable option has no brand config/resolvers. |
| Android Theme.kt/tokens | Partial | VerityProTheme(mode,dynamicColor,content) provides fixed tokens; light brandDefault=0xFF0400E5. No override/local. |
| Android redesign components | Complete | Button, progress, scaffold consume MaterialTheme.verityColors, so root override will propagate. |
| Android router/prototype | Partial | verification_flow_router.kt is a stage router, not Compose root. Actual legacy root is ui/verification/verification_start.kt:298; redesign root ui/redesign/VerityVerification.kt:30. Proto welcome has no logo/direct Proto.Brand; normal legacy welcome is ui/verification/intro_screen.kt. |
| Android Gradle | Complete | Compose is present; Coil is not declared. |
| Flutter lib/models.dart | Complete | Optional-field VerityOption.toMap exists; no brand model/keys. |
| Flutter lib/verity.dart | Complete | All starts derive arguments from options.toMap; correctly added keys reach launches. |
| Flutter plugins | Partial | Both manually create native options/roots and drop brand keys. |
| Web reference | Complete | HostedGeneral.tsx and HostedConfirmation.tsx apply typed primaryColor/logoUrl to CTAs, confirmation accent, and logo slots. |

| Dimension | iOS current | Android current | Flutter current | Web target | Gap |
|---|---|---|---|---|---|
| Primary colour | Fixed token + legacy constants | Fixed 0xFF0400E5 tokens | Inherits native | primaryColor | Not configurable through VerityOption. |
| Logo | No active welcome/intro image slot | No active intro image slot | Inherits native | logoUrl | Not exposed/rendered. |
| Config object | Absent | Absent | Absent | Equivalent props | Create optional nested model. |
| Bridge | N/A | Manual extraction | toMap bridge | N/A | Extend map and both plugins. |

Root cause: no configuration-model → Flutter bridge → plugin → native root-theme path exists. Fixed colours are a consequence, not the root cause.

### Live server audit (31 Aug 2026 AEST)

- docker ps: KYC Integration, Customer.Web, and related services are up/healthy.
- localhost:3044/health: Healthy; PostgreSQL, RabbitMQ/MassTransit and OCR circuit breaker healthy; zero fallback activations.
- localhost:3033/: HTTP 200.
- Unhealthy scan: No unhealthy containers.

No server change or deployment blocker exists. The SSH post-quantum key-exchange warning is deferred infrastructure hardening.

## 3. Scope

- In: optional config on all SDK APIs; validation; Flutter forwarding; root token overrides; primary controls/progress/active liveness ring; cached welcome/intro logo and safe placeholders; tests.
- Out: fonts, per-screen/dark variants, hosted-web/back-end changes, persistence/audit events, and decision changes.
- Prerequisites: macOS/Xcode CI, Android Coil dependency resolution, controlled HTTPS image test endpoint.

## 4. Compliance gates

1. Accept only non-empty #RGB/#RRGGBB (leading # optional); invalid input uses existing default and emits redacted WARN.
2. Accept only absolute HTTPS logo URLs. Reject HTTP, malformed, or user-info URLs before requesting; log host/reason only.
3. Image failure renders a shipped placeholder and emits redacted WARN; it never presents a verification error screen.
4. Never override error/warning/success/capture-quality/liveness success-or-fail regulatory colours.
5. Brand data stays in-memory UI configuration; never send to KYC, liveness, AML, scoring, or audit APIs.
6. Never log full URLs, API keys, or applicant data.

## 5. Files to create or modify

### iOS

- CREATE verity-pro-ios/VerityPro/models/VpBrandConfig.swift: public Sendable optional model with strict resolvers and a narrow UI logger adapter.
- MODIFY verity-pro-ios/VerityPro/models/VerityOption.swift: trailing brandConfig: VpBrandConfig? = nil; do not add toPayload.
- MODIFY verity-pro-ios/VerityPro/redesign/VerityTheme.swift: brand environment key and VerityColors.overridingBrand; let .verityTheme accept config and derive light/dark tokens once.
- MODIFY verity-pro-ios/VerityPro/utils/verity_pro.swift and redesign/VerityVerificationView.swift: pass config to redesign, legacy, and server-driven roots.
- MODIFY redesign/screens/VerityScreens.swift: actual redesign welcome logo with AsyncImage loading/default/error placeholders and redacted WARN.
- MODIFY views/intro_screen.swift plus active legacy ctaBlue/buttonBackground sites: use environment primary and same actual legacy intro logo; preserve state semantics.

### Android

- MODIFY veritypro-sdk/.../utils/verity_options.kt: Parcelable VpBrandConfig and final optional brandConfig, strict resolver/redacted WARN.
- MODIFY .../ui/theme/VerityTokens.kt: VerityColors.overrideBrand(primary), copying only brand/progress/active-liveness/capture-in-progress/focus/link/processing tokens.
- MODIFY .../ui/theme/Theme.kt: brandConfig, LocalVerityBrandConfig, overridden tokens and Material/system-bar primary.
- MODIFY .../ui/verification/verification_start.kt and .../ui/redesign/VerityVerification.kt: actual roots pass config to VerityProTheme.
- MODIFY .../ui/verification/intro_screen.kt: actual welcome logo with Coil loading/error/default state.
- MODIFY prototype files only if packaged/reachable; otherwise remove production exposure/document exclusion.
- MODIFY veritypro-sdk/build.gradle: add pinned Compose-compatible Coil dependency; it is absent.

### Flutter

- MODIFY verity_flutter_sdk/lib/models.dart: const VpBrandConfig, VerityOption.brandConfig, and nullable brandPrimaryColor/brandLogoUrl map keys.
- DOCUMENT/test only lib/verity.dart; it already forwards toMap. No change to verity_method_channel.dart.
- MODIFY both native VerityPlugin files: centralize extraction, add config to every native option, and thread it into direct address/EDD/combined roots.

## 6. Implementation sequence

1. Add models, resolver tests, exact map contract.
2. Add iOS/Android root token overrides; preserve nil defaults exactly.
3. Convert every active hard-coded primary site to environment/local tokens.
4. Add cached logo component to actual welcome/intro.
5. Extend Flutter serialization and both plugins for every route.
6. Run builds and smoke matrix.

## 7. Data contracts

    Swift:  VpBrandConfig(primaryColor: String? = nil, logoUrl: String? = nil)
    Kotlin: @Parcelize data class VpBrandConfig(val primaryColor: String? = null, val logoUrl: String? = null) : Parcelable
    Dart:   const VpBrandConfig({String? primaryColor, String? logoUrl})

VerityOption.brandConfig is optional/final or trailing. Flutter keys are exactly brandPrimaryColor and brandLogoUrl. No API, database, event, migration or idempotency changes.

## 8. Test requirements

- iOS/Android: valid 3/6 digit hex; blank/nil/invalid hex; valid HTTPS; HTTP; malformed/blank URL; redacted-warning assertion.
- Android: Parcelable round-trip; overrideBrand changes only whitelisted tokens/preserves regulatory tokens.
- Flutter: key flattening/null omission and every launch-route plugin forwarding.
- UI: #FF5500 applies to buttons/progress/current-step/active liveness; nil remains blue; valid logo renders; colour-only gives default logo; 404 placeholder/WARN/no crash; HTTP no request/WARN; invalid hex defaults/WARN.
- Gates: Android unit/release build, Xcode build/test, flutter analyze/test, plugin-example integration build all zero-error.

## 9. Definition of done

- [ ] Optional nested config exists in all SDKs.
- [ ] Flutter keys reach every native route.
- [ ] Nil configuration is visually unchanged.
- [ ] Primary controls/progress/active liveness use root-derived tokens.
- [ ] Regulatory colours and decisions are unchanged.
- [ ] Actual welcome/intro displays HTTPS logo or placeholder.
- [ ] Validation/load failures produce redacted WARN and no crash.
- [ ] Coil resolves before its use.
- [ ] All tests/builds have zero errors.

## 10. Open questions

None. Confirm only whether prototype compositions are packaged/reachable before treating them as production theming surfaces.

## 11. Solution quality verdict

| Quality check | Status | Evidence |
|---|---|---|
| Band-aid / symptom patch | ABSENT | Adds full public-model-to-native-root path. |
| Fallback masking broken primary | ABSENT | Valid config uses primary theme path; placeholders are intended image-render states. |
| Graceful silent failure | ABSENT | Invalid/load paths require redacted WARN diagnostics. |
| Mock / stub in production | ABSENT | Uses real image loader/cache and composition locals. |
| Unrealistic for stack | ABSENT | SwiftUI environment/AsyncImage and Compose plus declared Coil fit stack. |
| Root cause identified | YES | Models/bridges/root themes lack propagation. |
| Industry-standard patterns | YES | Immutable config, root token override, optional compatibility, cached asset. |
| All error paths handled | YES | Explicit validation/loading diagnostics and deterministic placeholders. |
