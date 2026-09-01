package com.mdmesh.core.sync

import com.mdmesh.core.command.CommandDispatcher
import com.mdmesh.core.store.AppliedRevisionStore
import com.mdmesh.core.store.KioskStateStore
import com.mdmesh.core.telemetry.EventSink
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandStatus
import com.mdmesh.proto.DesiredState
import com.mdmesh.proto.EventType
import com.mdmesh.proto.KioskApplyPayload
import com.mdmesh.proto.ProtocolJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Brings the device in line with the [DesiredState] the server sends on every check-in.
 *
 * The difference from a command is the whole point. A command is an order given once: a device
 * switched off when it was issued never receives it, and nothing says so. The desired state is a
 * description of how the device should *be*, repeated every time it calls home — so one that was
 * away for days aligns itself on its first check-in, and one whose settings were changed by hand
 * is put right on the next round.
 *
 * **It applies nothing itself.** Entering kiosk is delicate work — allowlists, lock-task feature
 * flags, the HOME component claim, the crash-loop guard — and it already lives in
 * `KioskEnterHandler`. Re-implementing it here would mean two versions of that logic drifting
 * apart, and the one that drifts is always the one nobody is watching. So this synthesises the
 * very commands the handlers already know, and hands them to the same [CommandDispatcher] the
 * server's commands go through.
 *
 * Synthesised commands are **not** acked to the server: it never issued them, and reporting
 * results for command ids it does not know would be noise in the queue. What the operator sees
 * instead is a timeline event, which is the thing that was missing — a way to tell, from the
 * console, that a saved setting actually reached the device.
 */
@Singleton
class DesiredStateReconciler @Inject constructor(
    private val dispatcher: CommandDispatcher,
    private val kioskState: KioskStateStore,
    private val appliedRevision: AppliedRevisionStore,
    private val eventSink: EventSink,
) {

    suspend fun reconcile(desired: DesiredState?) {
        if (desired == null) return
        // Same revision: the desired state has not changed since it was applied, so there is
        // nothing to do. This is what stops the kiosk being re-entered on every check-in.
        if (desired.revision != 0L && desired.revision == appliedRevision.load()) return

        val statuses = mutableListOf<CommandStatus>()
        statuses += reconcileKiosk(desired.kiosk)
        statuses += reconcilePolicies(desired.policies)

        if (statuses.isEmpty()) {
            // Nothing differed. Record the revision anyway so the comparison short-circuits from
            // now on instead of re-deriving "nothing to do" at every check-in.
            appliedRevision.save(desired.revision)
            return
        }

        // A failure is worth retrying on the next check-in, so the revision is not recorded and
        // the work happens again. "unsupported" is not a failure in that sense: the device simply
        // cannot do it - Device Owner missing, an Android version without the API - and retrying
        // for ever would just fill the timeline with the same line.
        val failed = statuses.any { it == CommandStatus.FAILED }
        if (!failed) appliedRevision.save(desired.revision)

        eventSink.record(
            EventType.DESIRED_RECONCILED,
            "revision=${desired.revision} applied=${statuses.size} failed=$failed",
        )
    }

    /**
     * Kiosk: enter when the wanted one differs from what is applied, leave when none is wanted.
     * Comparison is on the payload as a whole — the fields are few and a change to any of them
     * means re-entering anyway.
     */
    private suspend fun reconcileKiosk(wanted: KioskApplyPayload?): List<CommandStatus> {
        val current = runCatching { kioskState.load() }.getOrNull()
        return when {
            wanted == null && current == null -> emptyList()
            wanted == null -> listOf(dispatch("kiosk.exit", null, "kiosk.exit"))
            wanted == current -> emptyList()
            else -> {
                val payload = ProtocolJson.json.encodeToJsonElement(
                    KioskApplyPayload.serializer(), wanted,
                ) as? JsonObject
                if (payload == null) {
                    // Never enter kiosk with no payload: the handler would fall back to its
                    // defaults - launcher mode, empty allowlist - and pin the device to nothing.
                    // Report it as a failure so the revision is not recorded and the next
                    // check-in tries again.
                    eventSink.record(EventType.DESIRED_RECONCILED, "kiosk payload not serialisable")
                    listOf(CommandStatus.FAILED)
                } else {
                    listOf(dispatch("kiosk.enter", payload, "kiosk.enter"))
                }
            }
        }
    }

    /**
     * Toggles are applied whenever the revision moved, without tracking each one separately:
     * they are idempotent, there are a handful of them, and a per-policy applied-state store
     * would be a second thing to keep in step with the first.
     *
     * A key the device does not know degrades to `unsupported` in the dispatcher, exactly as it
     * would for a server-sent command. The registry is open on purpose.
     */
    private suspend fun reconcilePolicies(policies: Map<String, Boolean>): List<CommandStatus> =
        policies.map { (key, value) ->
            dispatch(
                type = "policy.apply",
                payload = buildJsonObject {
                    put("policy", JsonPrimitive(key))
                    put("value", JsonPrimitive(value))
                },
                capability = "policy.$key",
            )
        }

    private suspend fun dispatch(type: String, payload: JsonObject?, capability: String): CommandStatus =
        dispatcher.dispatch(
            CommandEnvelope(
                // A fresh id every time: the dispatcher runs each id at most once per process, so
                // a reused one would make the second reconcile a silent no-op.
                commandId = UUID.randomUUID().toString(),
                issuedAt = Instant.now().toString(),
                type = type,
                requiresCapability = capability,
                payload = payload,
            ),
        ).status
}
