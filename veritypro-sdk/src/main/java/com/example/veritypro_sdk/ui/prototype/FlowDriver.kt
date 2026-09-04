package com.example.veritypro_sdk.ui.prototype

import android.util.Log
import com.example.veritypro_sdk.services.ApiRepository
import com.example.veritypro_sdk.services.Resource
import com.example.veritypro_sdk.services.SessionStateResponse
import com.example.veritypro_sdk.ui.verification.VerityProViewModel
import com.example.veritypro_sdk.utils.VerityOption

/**
 * Flow-control seam for the neo-brutalist prototype flow ([ProtoVerificationScreen]).
 *
 * The prototype can run in two modes with an IDENTICAL UI:
 *  - CLIENT-driven (today): the queue of modules is computed on-device from the requested
 *    [VerityOption.mode] / [VerityOption.requiredModules], and the KYC session is minted by
 *    createKyc (its id threads through to the document/liveness modules).
 *  - SERVER-driven: the backend /v2/sessions API owns the flow. The driver seeds the queue from
 *    the session's requestedSteps (resuming past completedSteps) and, after each module submits,
 *    asks the backend for the next step. Mirrors the proven web Orchestrator
 *    (VerityPro-Customer.Web/src/app/hosted-verify/proto/Orchestrator.tsx).
 *
 * The screen renders modules the same way in both modes; only WHERE the module order and the
 * engine session id come from differs. That is exactly what this interface abstracts.
 */
interface FlowDriver {
    /** Ordered modules to render (subset/permutation of DOCUMENT / BIOMETRIC / ADDRESS / EDD). */
    suspend fun start(): List<String>

    /**
     * Called after [module] has submitted its evidence successfully. Returns the NEXT module to
     * render, or null when the flow is complete (proceed to the submitting/terminal screen).
     *
     * [stepData] is the per-step completion payload the SERVER-driven flow forwards to
     * /steps/{STEP}/complete (e.g. { AddressSessionId } for ADDRESS, the security-assessment fields
     * for EDD). The CLIENT driver ignores it — client mode never uses server step-complete.
     */
    suspend fun completeModule(module: String, stepData: Map<String, Any?>? = null): String?

    /**
     * The KYC engine session id threaded to downstream modules (document upload keying, begin-liveness).
     * Null when no engine session exists yet.
     */
    fun engineSessionId(): String?

    /**
     * The v2 VerificationSession id of the CURRENT server-driven session, or null for client-driven.
     * A host persists this after a DOCUMENT-bearing run so a later RETURNING-USER step (EDD-only, or
     * BIOMETRIC-only liveness step-up) can pass it back as previousSessionId. The backend then runs
     * EXACTLY the requested step instead of auto-prepending identity (see AutoPromoteSteps), because
     * identity is already established by the prior session. Default null so client callers are unaffected.
     */
    fun serverSessionId(): String? = null
}

/** Canonical module order — matches the web Orchestrator's CANONICAL_ORDER. */
private val CANONICAL_ORDER = listOf("DOCUMENT", "BIOMETRIC", "ADDRESS", "EDD")

/** Filter [steps] to recognized modules in canonical order (case-insensitive). May be empty. */
private fun orderSteps(steps: List<String>?): List<String> {
    val set = steps.orEmpty().map { it.uppercase() }.toSet()
    return CANONICAL_ORDER.filter { it in set }
}

/**
 * CLIENT-driven driver — a pure, behaviour-preserving extraction of the prototype's existing logic.
 *
 * [start] returns exactly what `protoModuleOrder(options)` returned before this refactor, and
 * [completeModule] walks that same queue by a local index (the old `advanceModule()` did
 * `moduleIndex + 1` over the same list). [engineSessionId] is the createKyc session id
 * (`vm.getSessionId()`), which is what the document + liveness paths already used.
 */
class ClientFlowDriver(
    private val options: VerityOption,
    private val vm: VerityProViewModel,
) : FlowDriver {

    private var queue: List<String> = emptyList()
    private var index: Int = 0

    override suspend fun start(): List<String> {
        queue = protoModuleOrder(options)
        index = 0
        return queue
    }

    override suspend fun completeModule(module: String, stepData: Map<String, Any?>?): String? {
        // stepData is ignored — client mode never posts to the server step-complete endpoint.
        // Advance from the CURRENT module. The old advanceModule() incremented a shared index;
        // here we locate the completed module and return its successor, so an out-of-order call
        // (e.g. a resumed queue) still resolves against the real queue rather than a stale counter.
        val at = queue.indexOf(module).let { if (it >= 0) it else index }
        index = at + 1
        return queue.getOrNull(index)
    }

    override fun engineSessionId(): String? = vm.getSessionId().takeIf { it.isNotBlank() }
}

/**
 * SERVER-driven driver — the backend /v2/sessions API owns the flow. Mirrors the web Orchestrator:
 *  - [start] creates the session (or fetches [VerityOption.serverSessionId] when already created),
 *    then returns requestedSteps MINUS completedSteps (resume-aware) in canonical order.
 *  - [completeModule] calls /steps/{STEP}/complete and returns nextAction?.step (null = done).
 *  - [engineSessionId] is the latest session state's kycEngineSessionId — the id the document
 *    module must key update-kyc-verification against (rather than createKyc minting a new one).
 *
 * The repository already maps a 409 (concurrent-modify) to a friendly Resource.Error; this driver
 * surfaces any completion/creation Resource.Error as an exception so the screen's existing
 * try/catch → error stage handles it (matching the web Orchestrator's throw-on-error contract).
 */
class ServerFlowDriver(
    private val options: VerityOption,
    private val repository: ApiRepository,
) : FlowDriver {

    private var session: SessionStateResponse? = null

    override suspend fun start(): List<String> {
        val existingId = options.serverSessionId?.takeIf { it.isNotBlank() }
        val result = if (existingId != null) {
            repository.getV2SessionState(existingId, options.apiKey)
        } else {
            repository.createV2Session(options)
        }
        val state = when (result) {
            is Resource.Success -> result.data
            is Resource.Error -> throw IllegalStateException(result.message)
            else -> throw IllegalStateException("Couldn't start verification.")
        }
        session = state
        // SERVER-DRIVEN: the backend session's requestedSteps determines the flow. Resume-aware —
        // skip anything already marked completed (device handoff / refresh) so the user isn't asked
        // to redo finished checks.
        val requested = orderSteps(state.requestedSteps)
        val completed = state.completedSteps.map { it.uppercase() }.toSet()
        val pending = requested.filter { it !in completed }
        Log.d(
            "ServerFlowDriver",
            "start: session=${state.id} engine=${state.kycEngineSessionId} " +
                "requested=$requested completed=$completed pending=$pending",
        )
        return pending
    }

    override suspend fun completeModule(module: String, stepData: Map<String, Any?>?): String? {
        val sessionId = session?.id
            ?: throw IllegalStateException("No active session to complete step against.")
        val step = module.uppercase()
        // Forward the per-step payload the web Orchestrator sends: { AddressSessionId } for ADDRESS,
        // the security-assessment fields for EDD. DOCUMENT / BIOMETRIC pass null (no data).
        return when (val result = repository.completeV2Step(sessionId, step, options.apiKey, stepData)) {
            is Resource.Success -> {
                val state = result.data
                session = state
                // null nextAction == the backend considers the flow complete.
                val next = state.nextAction?.step?.uppercase()
                Log.d(
                    "ServerFlowDriver",
                    "completeModule($step): next=$next status=${state.status}",
                )
                next
            }
            is Resource.Error -> throw IllegalStateException(result.message)
            else -> throw IllegalStateException("Couldn't advance verification.")
        }
    }

    override fun engineSessionId(): String? = session?.kycEngineSessionId?.takeIf { it.isNotBlank() }

    // The v2 session id (VerificationSession.Id) — what the backend accepts as previousSessionId to
    // establish a returning-user session that runs EDD/BIOMETRIC standalone (no identity prepend).
    override fun serverSessionId(): String? = session?.id?.takeIf { it.isNotBlank() }
}
