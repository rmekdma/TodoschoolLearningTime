package com.example.todoschooltime.e2eobserver

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the read-only product snapshot seam without opening UI or making fixture requests. */
@RunWith(AndroidJUnit4::class)
class Ticket02ProviderDiagnosticsTest {
    @Test
    fun debugSnapshotProviderIsReadableWithoutOpeningProductUi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = context.contentResolver.call(PROVIDER_AUTHORITY, METHOD_SNAPSHOT, null, Bundle())
        assertNotNull("The debug diagnostics provider returned no snapshot", result)
        val serialized = result!!.getString(EXTRA_SNAPSHOT_JSON)
        assertNotNull("The debug diagnostics provider omitted its snapshot", serialized)
        val snapshot = JSONObject(serialized!!)

        assertEquals(PRODUCT_PACKAGE, snapshot.getString("applicationId"))
        assertTrue("The product AccessibilityService lease is not attached", snapshot.getBoolean("serviceLeaseAttached"))
    }

    private companion object {
        const val PRODUCT_PACKAGE = "com.example.todoschooltime.debug"
        const val PROVIDER_AUTHORITY = "com.example.todoschooltime.debug.monitor-diagnostics"
        const val METHOD_SNAPSHOT = "snapshot"
        const val EXTRA_SNAPSHOT_JSON = "snapshotJson"
    }
}
