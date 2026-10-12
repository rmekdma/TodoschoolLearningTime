package com.example.todoschooltime

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.content.SharedPreferences
import java.util.concurrent.Executors

open class TodoSchoolApplication : Application() {
    lateinit var learningMonitor: LearningMonitor
        private set
    internal lateinit var learningMonitorRuntime: LearningMonitorRuntimeAdapter
        private set
    internal lateinit var learningMonitorScreenUseCases: LearningMonitorScreenUseCases
        private set
    internal lateinit var learningMonitorDiagnostics: LearningMonitorDiagnostics
        private set

    override fun onCreate() {
        super.onCreate()
        val commandExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "learning-monitor-serial").apply { isDaemon = true }
        }
        val workerExecutor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "learning-monitor-io").apply { isDaemon = true }
        }
        val timerExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "learning-monitor-timer").apply { isDaemon = true }
        }
        val mainHandler = Handler(Looper.getMainLooper())
        val monitorPreferences = getSharedPreferences(MONITOR_PREFERENCES, MODE_PRIVATE)
        val notificationIdPreferences = getSharedPreferences(NOTIFICATION_ID_PREFERENCES, MODE_PRIVATE)
        learningMonitorDiagnostics = LearningMonitorDiagnostics(enabled = BuildConfig.DEBUG)
        val targetCountStore = TargetCountStore(this)
        val learningReportRepository = createMonitorRepository()

        val engine = LearningMonitorEngine(
            commandQueue = MonitorTaskQueue { task -> commandExecutor.execute { task() } },
            workerQueue = MonitorTaskQueue { task -> workerExecutor.execute { task() } },
            callbackQueue = MonitorTaskQueue { task -> mainHandler.post { task() } },
            clock = createMonitorClock(),
            timerFactory = ScheduledMonitorTimerFactory(timerExecutor),
            store = SharedPreferencesMonitorStore(monitorPreferences),
            repository = learningReportRepository,
            targetProvider = MonitorTargetProvider { childName, subjectName ->
                targetCountStore.getTargetCount(childName, subjectName)
            },
            notificationSink = createNotificationSink(notificationIdPreferences),
            executionStatusProbe = MonitorExecutionStatusProbe {
                MonitorExecutionStatusInspector.inspect(this@TodoSchoolApplication)
            },
        )
        learningMonitor = engine
        val monitorRuntime = LearningMonitorRuntimeAdapter(this, engine, learningMonitorDiagnostics)
        learningMonitorRuntime = monitorRuntime
        learningMonitorScreenUseCases = monitorRuntime
    }

    protected open fun createMonitorRepository(): MonitorRepository = LearningReportRepository(learningMonitorDiagnostics)

    protected open fun createMonitorClock(): MonitorClock = object : MonitorClock {
        override fun today() = DateHelper.today()
        override fun checkedAt() = DateHelper.formatCheckedAt(DateHelper.nowZoned())
        override fun monotonicMillis() = SystemClock.elapsedRealtime()
    }

    protected open fun createNotificationSink(notificationIdPreferences: SharedPreferences): ChildNotificationSink =
        AndroidChildNotificationSink(
            this,
            SharedPreferencesNotificationIdAllocator(notificationIdPreferences),
            learningMonitorDiagnostics,
        )

    private companion object {
        const val MONITOR_PREFERENCES = "learning_monitor"
        const val NOTIFICATION_ID_PREFERENCES = "learning_monitor_notification_ids"
    }
}
