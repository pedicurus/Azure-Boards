package com.example.security

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.window.SecureFlagPolicy
import com.example.BuildConfig
import com.example.ui.components.SecureScreenEffect
import com.example.ui.components.isScreenCaptureProtectionEnabled
import com.example.ui.components.secureDialogFlagPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Unit and Robolectric tests for release-only `FLAG_SECURE` screen-capture protection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SecureScreenPolicyTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `screen-capture protection is enabled for release builds and disabled for debug builds`() {
        assertTrue(isScreenCaptureProtectionEnabled(isDebugBuild = false))
        assertFalse(isScreenCaptureProtectionEnabled(isDebugBuild = true))
        assertEquals(!BuildConfig.DEBUG, isScreenCaptureProtectionEnabled())
        assertTrue("Unit tests run against the debug variant", BuildConfig.DEBUG)
        assertFalse(isScreenCaptureProtectionEnabled())
    }

    @Test
    fun `dialog secure policy resolves to SecureOn when enabled and Inherit when disabled`() {
        assertEquals(SecureFlagPolicy.SecureOn, secureDialogFlagPolicy(enabled = true))
        assertEquals(SecureFlagPolicy.Inherit, secureDialogFlagPolicy(enabled = false))
        assertEquals(SecureFlagPolicy.Inherit, secureDialogFlagPolicy())
    }

    @Test
    fun `SecureScreenEffect adds FLAG_SECURE when enabled and clears it on dispose`() {
        var showProtectedScreen by mutableStateOf(true)

        composeRule.setContent {
            if (showProtectedScreen) {
                SecureScreenEffect(enabled = true)
            }
        }
        composeRule.waitForIdle()

        assertTrue(
            "FLAG_SECURE should be set while SecureScreenEffect(enabled = true) is composed",
            hasSecureFlag(composeRule.activity)
        )

        composeRule.runOnIdle {
            showProtectedScreen = false
        }
        composeRule.waitForIdle()

        assertFalse(
            "FLAG_SECURE should be cleared once SecureScreenEffect leaves composition",
            hasSecureFlag(composeRule.activity)
        )
    }

    @Test
    fun `SecureScreenEffect leaves FLAG_SECURE unset when disabled or in debug default`() {
        composeRule.setContent {
            SecureScreenEffect()
        }
        composeRule.waitForIdle()

        assertFalse(
            "FLAG_SECURE must remain unset in debug builds so previews render normally",
            hasSecureFlag(composeRule.activity)
        )
    }

    private fun hasSecureFlag(activity: ComponentActivity): Boolean {
        val flags = activity.window.attributes.flags
        return (flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
    }
}
