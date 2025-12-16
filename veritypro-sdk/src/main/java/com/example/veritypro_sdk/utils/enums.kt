package com.example.veritypro_sdk.utils

/** Represents the various stages of the VerityPro verification workflow.*/
enum class VerificationStage {

    /**[INTRO] : Welcome screen.*/
    INTRO,

    /** [ID_SELECTION] : Selects the type of ID document (e.g., Passport, Driver’s License).*/
    ID_SELECTION,

    /** [DOCUMENT_CAPTURE] : User is guided to capture clear images of the chosen ID document.*/
    DOCUMENT_CAPTURE,

    /** [SELFIE_CAPTURE] : User captures a live selfie for face matching against the ID.*/
    SELFIE_CAPTURE,

    /** [RESULT] : Final screen showing whether verification succeeded or failed.*/
    RESULT
}