package com.example.veritypro_sdk.utils

/**
 * Computes the ordered list of [VerificationStage]s based on the active
 * [VerityMode] or the legacy set of [VerificationModule]s.
 *
 * When a [VerityMode] is supplied it takes precedence; the stage list is
 * hard-coded per mode so every flow is deterministic.
 *
 * Mode-to-stage mapping:
 *   DOCUMENT      -> HEALTH_CHECK -> INTRO -> ID_SELECTION -> PROCESS_EXPLAINER -> DOCUMENT_CAPTURE -> THANK_YOU
 *   BIOMETRIC     -> HEALTH_CHECK -> INTRO -> ID_SELECTION -> PROCESS_EXPLAINER -> DOCUMENT_CAPTURE -> SELFIE_CAPTURE -> RESULT -> THANK_YOU
 *   LIVENESS_ONLY -> INTRO -> PROCESS_EXPLAINER -> SELFIE_CAPTURE -> RESULT -> THANK_YOU
 *   ADDRESS       -> INTRO -> PROCESS_EXPLAINER -> ADDRESS_DOCUMENT -> THANK_YOU
 *   EDD           -> INTRO -> PROCESS_EXPLAINER -> EDD_DOCUMENT -> THANK_YOU
 *   COMBINED      -> HEALTH_CHECK -> INTRO -> ID_SELECTION -> PROCESS_EXPLAINER -> DOCUMENT_CAPTURE -> SELFIE_CAPTURE -> RESULT -> ADDRESS_DOCUMENT -> EDD_DOCUMENT -> THANK_YOU
 *
 * Legacy module-based constructor still works for backward compatibility.
 */
class VerificationFlowRouter private constructor(
    private val orderedStages: List<VerificationStage>,
    private val enabledModules: Set<VerificationModule>
) {

    /** Legacy constructor — builds stages from enabled modules (no mode). */
    constructor(enabledModules: Set<VerificationModule>) : this(
        orderedStages = buildModuleStages(enabledModules),
        enabledModules = enabledModules
    )

    /** Mode-aware constructor — builds stages from [VerityMode]. */
    constructor(mode: VerityMode) : this(
        orderedStages = buildModeStages(mode),
        enabledModules = modulesForMode(mode)
    )

    /** Returns the next stage after [current], or null if [current] is the last stage. */
    fun nextStage(current: VerificationStage): VerificationStage? {
        val idx = orderedStages.indexOf(current)
        return if (idx >= 0 && idx < orderedStages.lastIndex) orderedStages[idx + 1] else null
    }

    /** Returns the previous stage before [current], or null if [current] is the first stage. */
    fun previousStage(current: VerificationStage): VerificationStage? {
        val idx = orderedStages.indexOf(current)
        return if (idx > 0) orderedStages[idx - 1] else null
    }

    /** Checks whether a given [module] is enabled. */
    fun isModuleEnabled(module: VerificationModule): Boolean = module in enabledModules

    /**
     * True when the active flow has NO [VerificationModule.BIOMETRIC] stage.
     *
     * This is the discriminator for the Bug 2 (doc-only auto-upload) fix in
     * `verification_start.kt`. BIOMETRIC-bearing flows (BIOMETRIC, COMBINED, and the
     * legacy DOCUMENT+BIOMETRIC module set) upload the captured document as part of
     * the post-liveness `updateKyc` call, so the document-capture/RESULT path must NOT
     * upload again — that would double-upload. When BIOMETRIC is absent (DOCUMENT, and
     * legacy DOCUMENT+ADDRESS / DOCUMENT+EDD sets) there is no liveness stage, so the
     * document-capture path is the only opportunity to fire the upload.
     *
     * Note: this returns true for biometric-absent, document-less flows too
     * (ADDRESS-only, EDD-only). That is intentional and matches the legacy behaviour —
     * the call site guards the actual upload behind a `documentFrontPage != null`
     * check, so no document means no upload even though this returns true.
     */
    fun shouldAutoUploadDocument(): Boolean = !isModuleEnabled(VerificationModule.BIOMETRIC)

    /** Returns the first content stage after INTRO (the first module-specific stage). */
    fun firstContentStage(): VerificationStage =
        orderedStages.firstOrNull { it != VerificationStage.HEALTH_CHECK && it != VerificationStage.INTRO }
            ?: VerificationStage.RESULT

    /** Returns all ordered stages for debugging / logging. */
    fun allStages(): List<VerificationStage> = orderedStages.toList()

    companion object {
        /** Build stages from legacy module set. */
        private fun buildModuleStages(enabledModules: Set<VerificationModule>): List<VerificationStage> = buildList {
            add(VerificationStage.HEALTH_CHECK)
            add(VerificationStage.INTRO)

            if (VerificationModule.DOCUMENT in enabledModules) {
                add(VerificationStage.ID_SELECTION)
                add(VerificationStage.DOCUMENT_CAPTURE)
            }
            if (VerificationModule.BIOMETRIC in enabledModules) {
                add(VerificationStage.SELFIE_CAPTURE)
            }
            if (VerificationModule.ADDRESS in enabledModules) {
                add(VerificationStage.ADDRESS_DOCUMENT)
            }
            if (VerificationModule.EDD in enabledModules) {
                add(VerificationStage.EDD_DOCUMENT)
            }

            add(VerificationStage.RESULT)
        }

        /** Build stages from a [VerityMode]. */
        private fun buildModeStages(mode: VerityMode): List<VerificationStage> = when (mode) {
            VerityMode.DOCUMENT -> listOf(
                VerificationStage.HEALTH_CHECK,
                VerificationStage.INTRO,
                VerificationStage.ID_SELECTION,
                VerificationStage.PROCESS_EXPLAINER,
                VerificationStage.DOCUMENT_CAPTURE,
                VerificationStage.THANK_YOU
            )
            VerityMode.BIOMETRIC -> listOf(
                VerificationStage.HEALTH_CHECK,
                VerificationStage.INTRO,
                VerificationStage.ID_SELECTION,
                VerificationStage.PROCESS_EXPLAINER,
                VerificationStage.DOCUMENT_CAPTURE,
                VerificationStage.SELFIE_CAPTURE,
                VerificationStage.RESULT,
                VerificationStage.THANK_YOU
            )
            VerityMode.LIVENESS_ONLY -> listOf(
                VerificationStage.INTRO,
                VerificationStage.PROCESS_EXPLAINER,
                VerificationStage.SELFIE_CAPTURE,
                VerificationStage.RESULT,
                VerificationStage.THANK_YOU
            )
            VerityMode.ADDRESS -> listOf(
                VerificationStage.INTRO,
                VerificationStage.PROCESS_EXPLAINER,
                VerificationStage.ADDRESS_DOCUMENT,
                VerificationStage.THANK_YOU
            )
            VerityMode.EDD -> listOf(
                VerificationStage.INTRO,
                VerificationStage.PROCESS_EXPLAINER,
                VerificationStage.EDD_DOCUMENT,
                VerificationStage.THANK_YOU
            )
            VerityMode.COMBINED -> listOf(
                VerificationStage.HEALTH_CHECK,
                VerificationStage.INTRO,
                VerificationStage.ID_SELECTION,
                VerificationStage.PROCESS_EXPLAINER,
                VerificationStage.DOCUMENT_CAPTURE,
                VerificationStage.SELFIE_CAPTURE,
                VerificationStage.RESULT,
                VerificationStage.ADDRESS_DOCUMENT,
                VerificationStage.EDD_DOCUMENT,
                VerificationStage.THANK_YOU
            )
            // SERVER_DRIVEN: minimal stage list — the server controls the flow.
            VerityMode.SERVER_DRIVEN -> listOf(
                VerificationStage.INTRO,
                VerificationStage.THANK_YOU
            )
            // STEP_UP_AUTH: no intro or document capture — jump straight to liveness.
            VerityMode.STEP_UP_AUTH -> listOf(
                VerificationStage.SELFIE_CAPTURE,
                VerificationStage.THANK_YOU
            )
        }

        /** Derive the set of modules implied by a [VerityMode]. */
        private fun modulesForMode(mode: VerityMode): Set<VerificationModule> = when (mode) {
            VerityMode.DOCUMENT -> setOf(VerificationModule.DOCUMENT)
            VerityMode.BIOMETRIC -> setOf(VerificationModule.DOCUMENT, VerificationModule.BIOMETRIC)
            VerityMode.LIVENESS_ONLY -> setOf(VerificationModule.BIOMETRIC)
            VerityMode.ADDRESS -> setOf(VerificationModule.ADDRESS)
            VerityMode.EDD -> setOf(VerificationModule.EDD)
            VerityMode.COMBINED -> setOf(
                VerificationModule.DOCUMENT,
                VerificationModule.BIOMETRIC,
                VerificationModule.ADDRESS,
                VerificationModule.EDD
            )
            VerityMode.SERVER_DRIVEN -> setOf(
                VerificationModule.DOCUMENT,
                VerificationModule.BIOMETRIC,
                VerificationModule.ADDRESS,
                VerificationModule.EDD
            )
            VerityMode.STEP_UP_AUTH -> setOf(VerificationModule.BIOMETRIC)
        }
    }
}
