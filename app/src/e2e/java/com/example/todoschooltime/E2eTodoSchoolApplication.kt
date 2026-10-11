package com.example.todoschooltime

import android.content.SharedPreferences

class E2eTodoSchoolApplication : TodoSchoolApplication() {
    internal lateinit var e2eWorld: E2eWorldStore
        private set

    override fun onCreate() {
        e2eWorld = E2eWorldStore(this)
        CredentialStore(this).apply {
            save(Credentials(E2eWorldStore.TEST_EMAIL, E2eWorldStore.TEST_PASSWORD))
            saveAccountId(E2eWorldStore.TEST_ACCOUNT_ID)
        }
        super.onCreate()
        learningMonitor.reauthenticate(
            E2eWorldStore.TEST_ACCOUNT_ID,
            E2eWorldStore.TEST_EMAIL,
            E2eWorldStore.TEST_PASSWORD,
        )
    }

    override fun createMonitorRepository(): MonitorRepository = E2eFakeRepository(e2eWorld)

    override fun createMonitorClock(): MonitorClock = E2eMonitorClock(e2eWorld)

    override fun createNotificationSink(notificationIdPreferences: SharedPreferences): ChildNotificationSink =
        E2eAuditedNotificationSink(this, e2eWorld, notificationIdPreferences)
}
