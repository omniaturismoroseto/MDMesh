package com.mdmesh.core.sync

import com.mdmesh.core.command.CommandDispatcher
import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.core.store.InMemoryAppliedRevisionStore
import com.mdmesh.core.store.InMemoryKioskStateStore
import com.mdmesh.core.telemetry.EventSink
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.DesiredState
import com.mdmesh.proto.KioskApplyPayload
import com.mdmesh.proto.KioskFeaturesDto
import com.mdmesh.proto.TelemetryEventDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reconciler is what makes a saved setting actually reach the device. Before it, the
 * configuration never left the server: an operator changed a switch, saved, and nothing
 * happened — with nothing anywhere to say so.
 *
 * These tests pin the behaviour that matters, and each one guards a way of getting it wrong
 * that would be invisible in use: re-applying for ever (a screen that flickers), never
 * retrying after a failure (a device stuck out of line), or retrying for ever something the
 * device simply cannot do.
 */
class DesiredStateReconcilerTest {

    /** Records what was dispatched and answers with a chosen outcome. */
    private class RecordingHandler(
        override val type: String,
        private val outcome: (CommandEnvelope) -> CommandResult = { CommandResults.done(it) },
    ) : CommandHandler {
        val seen = mutableListOf<CommandEnvelope>()
        override suspend fun handle(command: CommandEnvelope): CommandResult {
            seen += command
            return outcome(command)
        }
    }

    private class RecordingEvents : EventSink {
        val events = mutableListOf<Pair<String, String?>>()
        override fun record(type: String, detail: String?) {
            events += type to detail
        }
        override fun drain(): List<TelemetryEventDto> = emptyList()
        override fun restore(events: List<TelemetryEventDto>) = Unit
    }

    private val kioskPayload = KioskApplyPayload(
        mode = "single",
        allowedPackages = listOf("it.omniaadriatic.segnalazioni", "com.google.android.dialer"),
        pinPackage = "it.omniaadriatic.segnalazioni",
        features = KioskFeaturesDto(home = false, recents = false),
    )

    private fun reconciler(
        enter: RecordingHandler = RecordingHandler("kiosk.enter"),
        exit: RecordingHandler = RecordingHandler("kiosk.exit"),
        policy: RecordingHandler = RecordingHandler("policy.apply"),
        kioskState: InMemoryKioskStateStore = InMemoryKioskStateStore(),
        revisions: InMemoryAppliedRevisionStore = InMemoryAppliedRevisionStore(),
        events: RecordingEvents = RecordingEvents(),
    ): DesiredStateReconciler = DesiredStateReconciler(
        dispatcher = CommandDispatcher(listOf(enter, exit, policy)),
        kioskState = kioskState,
        appliedRevision = revisions,
        eventSink = events,
    )

    @Test
    fun `nothing to do when the revision is unchanged`() = runTest {
        // Without this the kiosk would be re-entered at every check-in, which is not a wasted
        // cycle: it is a screen that flickers under the hands of whoever is using the device.
        val enter = RecordingHandler("kiosk.enter")
        val revisions = InMemoryAppliedRevisionStore(initial = 42L)
        reconciler(enter = enter, revisions = revisions)
            .reconcile(DesiredState(kiosk = kioskPayload, revision = 42L))

        assertTrue("nessun comando doveva partire", enter.seen.isEmpty())
    }

    @Test
    fun `enters kiosk when the wanted one differs from the applied one`() = runTest {
        val enter = RecordingHandler("kiosk.enter")
        val revisions = InMemoryAppliedRevisionStore()
        reconciler(enter = enter, revisions = revisions)
            .reconcile(DesiredState(kiosk = kioskPayload, revision = 7L))

        assertEquals(1, enter.seen.size)
        assertEquals("kiosk.enter", enter.seen.first().type)
        assertEquals(7L, revisions.load())
    }

    @Test
    fun `does not re-enter a kiosk that already matches`() = runTest {
        // The revision moved (some other part of the configuration changed) but the kiosk is
        // already the wanted one: touching it would be visible and pointless.
        val enter = RecordingHandler("kiosk.enter")
        reconciler(enter = enter, kioskState = InMemoryKioskStateStore(kioskPayload))
            .reconcile(DesiredState(kiosk = kioskPayload, revision = 9L))

        assertTrue(enter.seen.isEmpty())
    }

    @Test
    fun `leaves the kiosk when none is wanted`() = runTest {
        val exit = RecordingHandler("kiosk.exit")
        reconciler(exit = exit, kioskState = InMemoryKioskStateStore(kioskPayload))
            .reconcile(DesiredState(kiosk = null, revision = 3L))

        assertEquals(1, exit.seen.size)
    }

    @Test
    fun `applies every managed toggle, and nothing else`() = runTest {
        // An absent key means "unmanaged", which is not the same as false: the device must be
        // left alone, not switched off.
        val policy = RecordingHandler("policy.apply")
        reconciler(policy = policy)
            .reconcile(DesiredState(policies = mapOf("wifi" to true, "bluetooth" to false), revision = 1L))

        assertEquals(2, policy.seen.size)
        val applied = policy.seen.map { it.payload.toString() }
        assertTrue(applied.any { it.contains("wifi") && it.contains("true") })
        assertTrue(applied.any { it.contains("bluetooth") && it.contains("false") })
    }

    @Test
    fun `a failure is retried on the next check-in`() = runTest {
        // The revision must not be recorded, or a transient failure would leave the device
        // permanently out of line with no sign of it.
        val failing = RecordingHandler("kiosk.enter") { CommandResults.failed(it, "no device owner yet") }
        val revisions = InMemoryAppliedRevisionStore()
        val r = reconciler(enter = failing, revisions = revisions)

        r.reconcile(DesiredState(kiosk = kioskPayload, revision = 5L))
        assertEquals("la revisione non va registrata dopo un fallimento", 0L, revisions.load())

        r.reconcile(DesiredState(kiosk = kioskPayload, revision = 5L))
        assertEquals("al giro dopo ci riprova", 2, failing.seen.size)
    }

    @Test
    fun `something the device cannot do is not retried for ever`() = runTest {
        // "unsupported" is terminal: no Device Owner, or an Android version without the API.
        // Retrying would only fill the timeline with the same line.
        val unsupported = RecordingHandler("kiosk.enter") { CommandResults.unsupported(it, "needs Device Owner") }
        val revisions = InMemoryAppliedRevisionStore()
        val r = reconciler(enter = unsupported, revisions = revisions)

        r.reconcile(DesiredState(kiosk = kioskPayload, revision = 5L))
        r.reconcile(DesiredState(kiosk = kioskPayload, revision = 5L))

        assertEquals(1, unsupported.seen.size)
        assertEquals(5L, revisions.load())
    }

    @Test
    fun `records a timeline event, which is what was missing`() = runTest {
        val events = RecordingEvents()
        reconciler(events = events).reconcile(DesiredState(kiosk = kioskPayload, revision = 11L))

        val recorded = events.events.map { it.first }
        assertTrue("l'operatore deve poter vedere che e' avvenuta", recorded.contains("desiredReconciled"))
    }

    @Test
    fun `an older server sending nothing changes nothing`() = runTest {
        val enter = RecordingHandler("kiosk.enter")
        val revisions = InMemoryAppliedRevisionStore(initial = 4L)
        reconciler(enter = enter, revisions = revisions).reconcile(null)

        assertTrue(enter.seen.isEmpty())
        assertEquals(4L, revisions.load())
    }
}
