package io.github.ddastoor.eventtracker.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.ddastoor.eventtracker.AppContainer
import io.github.ddastoor.eventtracker.EventTrackerApp
import io.github.ddastoor.eventtracker.MainActivity
import io.github.ddastoor.eventtracker.data.Attachment
import io.github.ddastoor.eventtracker.data.Event
import io.github.ddastoor.eventtracker.data.EventType
import io.github.ddastoor.eventtracker.data.LocalStore
import io.github.ddastoor.eventtracker.data.Snapshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.random.Random

/** App with a signed-in account and realistic data, never talking to Google. */
class SeededApp : EventTrackerApp() {
    override fun createContainer(): AppContainer {
        seed(this)
        return AppContainer(this, connectToGoogle = false)
    }

    companion object {
        fun seed(ctx: Context) {
            ctx.getSharedPreferences("eventtracker", Context.MODE_PRIVATE).edit().putString("account", "me@gmail.com").commit()
            val now = System.currentTimeMillis() / 1000
            val day = 86_400L
            val rnd = Random(7)
            val types = listOf(
                EventType("haircut", "Haircut", 0xFF1E5AA8, "✂️", true, 0),
                EventType("gym", "Gym", 0xFF2E9E6A, "🏋️", true, 0),
                EventType("oil", "Oil change", 0xFFE08A1E, "🚗", true, 0),
                EventType("dentist", "Dentist", 0xFF7B4FC9, "🦷", true, 0),
                EventType("appliance", "New appliance", 0xFFD3455B, "🧊", false, 0),
            )
            val events = mutableListOf<Event>()
            var t = now - 720 * day
            var n = 0
            while (t < now - 10 * day) { events += Event("h${n++}", t, "haircut"); t += (24 + rnd.nextInt(20)) * day }
            t = now - 100 * day
            while (t < now - day) { events += Event("g${n++}", t + rnd.nextLong(3600 * 6), "gym"); t += (1 + rnd.nextInt(4)) * day }
            t = now - 600 * day
            while (t < now) { events += Event("o${n++}", t, "oil", note = "Synthetic 5W-30"); t += 120 * day }
            events += Event("d1", now - 200 * day, "dentist", note = "Cleaning")
            events += Event("d2", now - 20 * day, "dentist", note = "Checkup, all good")
            events += Event(
                "fridge", now - 3600, "appliance", note = "Fridge — 5 year warranty",
                attachment = Attachment("receiptFileId123", "fridge-receipt.png", "image/png"),
            )
            LocalStore(File(ctx.filesDir, "data")).saveSnapshot(Snapshot(types, events))
            writeReceipt(File(ctx.cacheDir, "attachments/receiptFileId123"))
        }

        private fun writeReceipt(file: File) {
            file.parentFile!!.mkdirs()
            val bmp = Bitmap.createBitmap(600, 820, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(0xFFFDFBF5.toInt())
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF222222.toInt(); textSize = 34f; typeface = Typeface.MONOSPACE }
            val lines = listOf(
                "  APPLIANCE MART", "  ---------------", "", "Invoice #48213", "",
                "Fridge FX-500     1,299.00", "Delivery             49.00", "Haul-away            25.00", "",
                "TOTAL           $1,373.00", "", "Paid: VISA ****1234", "Warranty: 5 years", "", "  Thank you!",
            )
            lines.forEachIndexed { i, s -> c.drawText(s, 40f, 80f + i * 46f, p) }
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}

/** App with nobody signed in. */
class SignedOutApp : EventTrackerApp() {
    override fun createContainer(): AppContainer {
        getSharedPreferences("eventtracker", Context.MODE_PRIVATE).edit().clear().commit()
        return AppContainer(this, connectToGoogle = false)
    }
}

private const val OUT = "build/screenshots"

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = SeededApp::class)
class MainScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun screens() {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$OUT/1_quick_add.png")

        compose.onNodeWithText("Events").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$OUT/2_events_with_file.png")

        compose.onNodeWithContentDescription("Next (older) event").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$OUT/3_events_next.png")

        compose.onNodeWithText("Charts").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onRoot().captureRoboImage("$OUT/4_charts_haircut_month.png")

        compose.onNodeWithText("Gym").performClick() // add a second series
        compose.onNodeWithText("Week").performClick()
        compose.onNodeWithText("3 months").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onRoot().captureRoboImage("$OUT/5_charts_two_types_week.png")

        compose.onNodeWithText("Types").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$OUT/6_types.png")

        compose.onNodeWithText("Quick add").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("New event").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$OUT/7_new_event.png")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = SignedOutApp::class)
class SignInScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun signIn() {
        compose.onNodeWithText("First-time setup info").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$OUT/0_sign_in.png")
    }
}
