package com.example.veritypro_sdk.utils

/** Modules that can be enabled/disabled per integration. */
enum class VerificationModule {
    /** ID selection + document capture */
    DOCUMENT,
    /** AWS liveness / selfie capture */
    BIOMETRIC,
    /** Proof of address document upload (camera or gallery) */
    ADDRESS,
    /** Enhanced Due Diligence document upload (camera or gallery) */
    EDD
}

/** Represents the various stages of the VerityPro verification workflow. */
enum class VerificationStage {

    /** [HEALTH_CHECK] : Validates ML backend is reachable before proceeding. */
    HEALTH_CHECK,

    /** [INTRO] : Welcome screen. */
    INTRO,

    /** [ID_SELECTION] : Selects the type of ID document — DOCUMENT module. */
    ID_SELECTION,

    /** [PROCESS_EXPLAINER] : Explains what steps will happen based on the current mode. */
    PROCESS_EXPLAINER,

    /** [DOCUMENT_CAPTURE] : User captures images of the chosen ID — DOCUMENT module. */
    DOCUMENT_CAPTURE,

    /** [SELFIE_CAPTURE] : User captures a live selfie for face matching — BIOMETRIC module. */
    SELFIE_CAPTURE,

    /** [ADDRESS_DOCUMENT] : Upload proof of address via camera or gallery — ADDRESS module. */
    ADDRESS_DOCUMENT,

    /** [EDD_DOCUMENT] : Upload EDD supporting document via camera or gallery — EDD module. */
    EDD_DOCUMENT,

    /** [RESULT] : Final screen showing whether verification succeeded or failed. */
    RESULT,

    /** [THANK_YOU] : Universal thank-you screen after successful submission. */
    THANK_YOU
}