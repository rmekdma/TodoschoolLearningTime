package com.example.todoschooltime.e2eobserver

import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Short, fixture-free probe for the test APK's bounded screen wake lock and observer boundary. */
@RunWith(AndroidJUnit4::class)
class Ticket03WakeLockProbeTest {
    @Test
    fun observerWakeLockKeepsCurrentForegroundAndReleases() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val observerContext = instrumentation.targetContext
        assertEquals(OBSERVER_PACKAGE, observerContext.packageName)
        assertTrue("The probe requires the screen to already be awake", isInteractive(observerContext))

        val automation = instrumentation.getUiAutomation(
            android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
        )
        val before = readProductState(observerContext)
        assertEquals(PRODUCT_PACKAGE, before.getString("applicationId"))
        assertTrue("The product service lease must be attached before the wake lock probe", before.getBoolean("serviceLeaseAttached"))
        val beforeCursor = lastSequence(before.getJSONArray("foregroundEvents"))
        val foregroundBefore = observedForegroundPackage(automation, before)

        val powerManager = instrumentation.context.getSystemService(PowerManager::class.java)
            ?: throw AssertionError("The observer test APK could not access PowerManager")
        val wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
            "$OBSERVER_PACKAGE:ticket03-wake-probe",
        ).apply { setReferenceCounted(false) }

        var cursorAfterAcquire = beforeCursor
        try {
            // WAKE_LOCK belongs to the external observer package manifest. A SecurityException is a probe failure.
            wakeLock.acquire(PROBE_TIMEOUT_MS)
            SystemClock.sleep(PROBE_SETTLE_MS)
            assertTrue("The observer test APK wake lock did not keep the display interactive", isInteractive(observerContext))

            val during = readProductState(observerContext)
            assertTrue("The product service lease detached during the wake lock probe", during.getBoolean("serviceLeaseAttached"))
            val foregroundDuring = observedForegroundPackage(automation, during)
            val eventsDuring = eventsAfter(during.getJSONArray("foregroundEvents"), beforeCursor)
            cursorAfterAcquire = lastSequence(during.getJSONArray("foregroundEvents"))
            assertEquals("Wake lock acquisition changed the foreground package", foregroundBefore, foregroundDuring)
            assertTrue(
                "Wake lock acquisition produced a foreground event for another package: $eventsDuring",
                eventsDuring.all { it.getString("packageName") == foregroundBefore },
            )

            val acquiredEvidence = JSONObject()
                .put("package", observerContext.packageName)
                .put("mechanism", "observer-test-apk SCREEN_BRIGHT_WAKE_LOCK")
                .put("timeoutMs", PROBE_TIMEOUT_MS)
                .put("foregroundPackageBefore", foregroundBefore)
                .put("foregroundPackageDuring", foregroundDuring)
                .put("foregroundEventCursorBefore", beforeCursor)
                .put("foregroundEventCursorDuring", lastSequence(during.getJSONArray("foregroundEvents")))
                .put("foregroundEventsDuringAcquire", JSONArray(eventsDuring))
                .put("serviceLeaseAttachedBefore", before.getBoolean("serviceLeaseAttached"))
                .put("serviceLeaseAttachedDuring", during.getBoolean("serviceLeaseAttached"))
            android.util.Log.i(RESULT_LOG_TAG, "TICKET03_WAKELOCK_PROBE_ACQUIRED=$acquiredEvidence")
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }

        assertFalse("The observer test APK wake lock was not released", wakeLock.isHeld)
        val after = readProductState(observerContext)
        assertTrue("The product service lease detached after the wake lock probe", after.getBoolean("serviceLeaseAttached"))
        val foregroundAfter = observedForegroundPackage(automation, after)
        val eventsAfterRelease = eventsAfter(after.getJSONArray("foregroundEvents"), cursorAfterAcquire)
        assertEquals("Wake lock release changed the foreground package", foregroundBefore, foregroundAfter)
        assertTrue(
            "Wake lock probe foreground events contain another package: $eventsAfterRelease",
            eventsAfterRelease.all { it.getString("packageName") == foregroundBefore },
        )
        android.util.Log.i(
            RESULT_LOG_TAG,
            "TICKET03_WAKELOCK_PROBE_RELEASED=" + JSONObject()
                .put("released", !wakeLock.isHeld)
                .put("foregroundPackageAfter", foregroundAfter)
                .put("foregroundEventCursorAfter", lastSequence(after.getJSONArray("foregroundEvents")))
                .put("foregroundEventsAfterRelease", JSONArray(eventsAfterRelease))
                .put("serviceLeaseAttachedAfter", after.getBoolean("serviceLeaseAttached")),
        )
    }

    private fun isInteractive(context: android.content.Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive == true

    private fun observedForegroundPackage(
        automation: android.app.UiAutomation,
        state: JSONObject,
    ): String {
        val activeWindowPackage = automation.rootInActiveWindow?.packageName?.toString()
        val latestEventPackage = state.getJSONArray("foregroundEvents").objects()
            .maxByOrNull { it.getLong("sequence") }
            ?.getString("packageName")
        return activeWindowPackage ?: latestEventPackage
            ?: throw AssertionError("The probe could not identify the current foreground package")
    }

    private fun readProductState(context: android.content.Context): JSONObject {
        val result = context.contentResolver.call(PROVIDER_AUTHORITY, PROVIDER_METHOD_SNAPSHOT, null, Bundle())
            ?: throw AssertionError("The debug read-only provider returned no snapshot")
        val serialized = result.getString(PROVIDER_EXTRA_SNAPSHOT_JSON)
            ?: throw AssertionError("The debug provider omitted its snapshot")
        return JSONObject(serialized)
    }

    private fun lastSequence(events: org.json.JSONArray): Long =
        events.objects().maxOfOrNull { it.getLong("sequence") } ?: 0L

    private fun eventsAfter(events: org.json.JSONArray, cursor: Long): List<JSONObject> =
        events.objects().filter { it.getLong("sequence") > cursor }

    private fun org.json.JSONArray.objects(): List<JSONObject> =
        (0 until length()).map { getJSONObject(it) }

    private companion object {
        const val OBSERVER_PACKAGE = "com.example.todoschooltime.e2eobserver"
        const val PRODUCT_PACKAGE = "com.example.todoschooltime.debug"
        const val PROVIDER_AUTHORITY = "com.example.todoschooltime.debug.monitor-diagnostics"
        const val PROVIDER_METHOD_SNAPSHOT = "snapshot"
        const val PROVIDER_EXTRA_SNAPSHOT_JSON = "snapshotJson"
        const val PROBE_TIMEOUT_MS = 30_000L
        const val PROBE_SETTLE_MS = 1_000L
        const val RESULT_LOG_TAG = "Ticket03WakeProbe"
    }
}
