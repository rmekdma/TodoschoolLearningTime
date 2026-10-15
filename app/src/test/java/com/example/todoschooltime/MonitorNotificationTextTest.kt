package com.example.todoschooltime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorNotificationTextTest {
    @Test
    fun statusTextContainsEverySubjectTimeAndErrorWithoutTurningUnknownCountIntoZero() {
        val child = MonitorChildSnapshot(
            childName = "철수",
            subjects = mapOf(
                "한글" to MonitorSubjectSnapshot("한글", MonitorSubjectStatus.CONFIRMATION_PENDING, 6),
                "수학" to MonitorSubjectSnapshot("수학", MonitorSubjectStatus.BELOW_TARGET, 5, activityCount = 2),
                "영어" to MonitorSubjectSnapshot("영어", MonitorSubjectStatus.COMPLETE, 0),
            ),
            lastCheckedAt = "마지막 확인 10:15",
            error = "영어 결과 확인 실패",
        )

        val text = MonitorNotificationText.body(child)
        assertTrue(text.contains("한글 확인 대기"))
        assertTrue(text.contains("수학 미달성 2/5"))
        assertTrue(text.contains("✔ 영어 완료 · 목표 0"))
        assertTrue(MonitorNotificationText.summary(child).contains("✔ 영어 완료"))
        assertTrue(text.contains("마지막 확인 10:15"))
        assertTrue(text.contains("오류: 영어 결과 확인 실패"))
        assertFalse(text.contains("0/6"))
        assertFalse(text.contains("0/0"))
    }
}
