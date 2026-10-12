package com.example.todoschooltime.e2eobserver

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Checks the Accessibility settings route without changing a permission or starting a fixture request. */
@RunWith(AndroidJUnit4::class)
class Ticket04AccessibilitySettingsNavigationTest {
    @Test
    fun downloadedAppsListCanOpenTheProductServiceWithoutChangingItsEnabledState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val before = readProductState(context)
        assertTrue("The product AccessibilityService must remain enabled for this navigation probe", before.getBoolean("accessibilityEnabled"))
        assertTrue("The product AccessibilityService lease must remain attached", before.getBoolean("serviceLeaseAttached"))
        assertTrue("Product notifications must be enabled before the navigation probe", before.getBoolean("notificationsEnabled"))
        assertTrue("POST_NOTIFICATIONS must remain granted", before.getBoolean("postNotificationsPermissionGranted"))
        assertSilentChannel(before)

        val label = Ticket04AccessibilitySettingsNavigator.openServiceSettingsPage(
            automation = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES),
            context = context,
            productPackage = PRODUCT_PACKAGE,
            serviceClass = PRODUCT_SERVICE_CLASS,
        )
        val after = readProductState(context)
        assertTrue("The product service label must be visible on its details page", label.isNotBlank())
        assertEquals("Accessibility navigation must not switch the product service off", before.getBoolean("accessibilityEnabled"), after.getBoolean("accessibilityEnabled"))
        assertEquals("Accessibility navigation must not detach the product service", before.getBoolean("serviceLeaseAttached"), after.getBoolean("serviceLeaseAttached"))
        assertEquals("Accessibility navigation must not change notification permission", before.getBoolean("notificationsEnabled"), after.getBoolean("notificationsEnabled"))
        assertEquals("Accessibility navigation must not change POST_NOTIFICATIONS", before.getBoolean("postNotificationsPermissionGranted"), after.getBoolean("postNotificationsPermissionGranted"))
        assertSilentChannel(after)
    }

    private fun assertSilentChannel(snapshot: JSONObject) {
        val channel = snapshot.getJSONObject("channel")
        assertTrue("The product status channel is missing", channel.getBoolean("exists"))
        assertTrue("The product status channel must use LOW/MIN importance", channel.getInt("importance") <= 2)
        assertTrue("The product status channel must keep vibration disabled", !channel.getBoolean("vibrationEnabled"))
    }

    private fun readProductState(context: android.content.Context): JSONObject {
        val result = context.contentResolver.call(PROVIDER_AUTHORITY, PROVIDER_METHOD_SNAPSHOT, null, Bundle())
            ?: throw AssertionError("The debug read-only diagnostics provider returned no snapshot")
        val serialized = result.getString(PROVIDER_EXTRA_SNAPSHOT_JSON)
            ?: throw AssertionError("The debug read-only diagnostics provider omitted its snapshot")
        return JSONObject(serialized)
    }

    private companion object {
        const val PRODUCT_PACKAGE = "com.example.todoschooltime.debug"
        const val PRODUCT_SERVICE_CLASS = "com.example.todoschooltime.MonitorAccessibilityService"
        const val PROVIDER_AUTHORITY = "com.example.todoschooltime.debug.monitor-diagnostics"
        const val PROVIDER_METHOD_SNAPSHOT = "snapshot"
        const val PROVIDER_EXTRA_SNAPSHOT_JSON = "snapshotJson"
    }
}
