package com.example.veritypro_sdk.utils

/**
 * Computes the ordered list of [VerificationStage]s based on which
 * [VerificationModule]s are enabled for the current session.
 *
 * Fixed ordering: HEALTH_CHECK -> INTRO -> [module stages] -> RESULT
 *
 * Module-to-stage mapping:
 *   DOCUMENT  -> [ID_SELECTION, DOCUMENT_CAPTURE]
 *   BIOMETRIC -> [SELFIE_CAPTURE]
 *   ADDRESS   -> [ADDRESS_DOCUMENT]
 *   EDD       -> [EDD_DOCUMENT]
 */
class VerificationFlowRouter(private val enabledModules: Set<VerificationModule>) {

    private val orderedStages: List<VerificationStage> = buildList {
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

    /** Returns the first content stage after INTRO (the first module-specific stage). */
    fun firstContentStage(): VerificationStage =
        orderedStages.getOrElse(2) { VerificationStage.RESULT }

    /** Returns all ordered stages for debugging / logging. */
    fun allStages(): List<VerificationStage> = orderedStages.toList()
}
