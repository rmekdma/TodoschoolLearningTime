package com.example.todoschooltime

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors

class TodoSchoolApplication : Application() {
    lateinit var learningMonitor: LearningMonitor
        private set
    internal lateinit var learningMonitorRuntime: LearningMonitorRuntimeAdapter
        private set
    internal lateinit var learningMonitorScreenUseCases: LearningMonitorScreenUseCases
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
        val targetCountStore = TargetCountStore(this)
        val learningReportRepository = LearningReportRepository()

        val engine = LearningMonitorEngine(
            commandQueue = MonitorTaskQueue { task -> commandExecutor.execute { task() } },
            workerQueue = MonitorTaskQueue { task -> workerExecutor.execute { task() } },
            callbackQueue = MonitorTaskQueue { task -> mainHandler.post { task() } },
            clock = object : MonitorClock {
                override fun today() = DateHelper.today()
                override fun checkedAt() = DateHelper.formatCheckedAt(DateHelper.nowZoned())
                override fun monotonicMillis() = SystemClock.elapsedRealtime()
            },
            timerFactory = ScheduledMonitorTimerFactory(timerExecutor),
            store = SharedPreferencesMonitorStore(monitorPreferences),
            repository = learningReportRepository,
            targetProvider = MonitorTargetProvider { childName, subjectName ->
                targetCountStore.getTargetCount(childName, subjectName)
            },
            notificationSink = AndroidChildNotificationSink(
                this,
                SharedPreferencesNotificationIdAllocator(notificationIdPreferences),
            ),
            executionStatusProbe = MonitorExecutionStatusProbe {
                MonitorExecutionStatusInspector.inspect(this@TodoSchoolApplication)
            },
        )
        learningMonitor = engine
        val monitorRuntime = LearningMonitorRuntimeAdapter(this, engine)
        learningMonitorRuntime = monitorRuntime
        learningMonitorScreenUseCases = monitorRuntime
    }

    private companion object {
        const val MONITOR_PREFERENCES = "learning_monitor"
        const val NOTIFICATION_ID_PREFERENCES = "learning_monitor_notification_ids"
    }
}
