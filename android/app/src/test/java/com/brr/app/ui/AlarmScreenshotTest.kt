package com.brr.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.brr.app.domain.TriggerKind
import com.brr.app.domain.model.ReminderLevel
import com.brr.app.domain.model.ThemeMode
import com.brr.app.reminder.ReminderContent
import com.brr.app.ui.alarm.AlarmScreen
import com.brr.app.ui.alarm.AlarmVariant
import com.brr.app.ui.theme.BrrTheme
import org.junit.Rule
import org.junit.Test

/** État stable (sans animation). Rendu réel des écrans immersifs Compose (layoutlib), pour revue visuelle sans téléphone. */
class AlarmScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, maxPercentDifference = 0.1)

    private fun content(level: ReminderLevel) = ReminderContent(
        taskId = 428, taskTitle = "Sortir les poubelles", message = "À ce stade, le sac-poubelle a plus de projets d'avenir que toi.",
        level = level, kind = TriggerKind.ESCALATION, isFinal = false, minutesLate = 47, nextAt = null, dueAt = null, snoozeCount = 3,
    )

    private fun shot(level: ReminderLevel, v: AlarmVariant) = paparazzi.snapshot(v.name) {
        BrrTheme(ThemeMode.DARK) { AlarmScreen(content(level), {}, {}, {}, {}, variant = v, motionOverride = false) }
    }

    @Test fun mission() = shot(ReminderLevel.HARCELEMENT, AlarmVariant.MISSION)
    @Test fun radar() = shot(ReminderLevel.HARCELEMENT, AlarmVariant.RADAR)
    @Test fun controle() = shot(ReminderLevel.HARCELEMENT, AlarmVariant.CONTROLE)
    @Test fun nationale() = shot(ReminderLevel.BRR, AlarmVariant.NATIONALE)
    @Test fun defcon() = shot(ReminderLevel.BRR, AlarmVariant.DEFCON)
    @Test fun flash() = shot(ReminderLevel.BRR, AlarmVariant.FLASH)
}
