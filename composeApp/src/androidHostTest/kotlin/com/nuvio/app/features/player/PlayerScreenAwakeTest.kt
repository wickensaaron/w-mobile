package com.nuvio.app.features.player

import android.app.Application
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayerScreenAwakeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun Window.keepsScreenAwake() = attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0

    @Test
    fun activePlaybackKeepsTheScreenAwakeWithoutDependingOnAVideoSurface() {
        val active = mutableStateOf(true)
        val visible = mutableStateOf(true)
        compose.setContent { if (visible.value) EnterImmersivePlayerMode(active.value) }
        compose.runOnIdle {
            assertTrue(compose.activity.window.keepsScreenAwake())
            active.value = false // Pause, ended or failed playback releases the request.
        }
        compose.runOnIdle {
            assertFalse(compose.activity.window.keepsScreenAwake())
            active.value = true // Resuming/buffering reacquires it.
        }
        compose.runOnIdle {
            assertTrue(compose.activity.window.keepsScreenAwake())
            visible.value = false
        }
        compose.runOnIdle { assertFalse(compose.activity.window.keepsScreenAwake()) }
    }

    @Test
    fun inactivePlayerDoesNotPreventNormalScreenTimeout() {
        compose.setContent { EnterImmersivePlayerMode(false) }
        compose.runOnIdle { assertFalse(compose.activity.window.keepsScreenAwake()) }
    }

    @Test
    fun leavingThePlayerPreservesAnExistingWindowRequest() {
        val visible = mutableStateOf(true)
        compose.runOnUiThread { compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        compose.setContent { if (visible.value) EnterImmersivePlayerMode(true) }
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle { assertTrue(compose.activity.window.keepsScreenAwake()) }
    }

    @Test
    fun fullscreenDialogKeepsItsOwnWindowAwakeAndReleasesItOnDismissal() {
        val visible = mutableStateOf(true)
        var playerWindow: Window? = null
        compose.setContent {
            if (visible.value) Dialog(onDismissRequest = {}) {
                val window = (LocalView.current.parent as DialogWindowProvider).window
                SideEffect { playerWindow = window }
                EnterImmersivePlayerMode(true)
            }
        }
        compose.runOnIdle {
            assertTrue(assertNotNull(playerWindow).keepsScreenAwake())
            assertFalse(compose.activity.window.keepsScreenAwake())
            visible.value = false
        }
        compose.runOnIdle { assertFalse(assertNotNull(playerWindow).keepsScreenAwake()) }
    }
}
