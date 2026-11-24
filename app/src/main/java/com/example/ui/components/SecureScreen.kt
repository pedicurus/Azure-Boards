package com.example.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.SecureFlagPolicy
import com.example.BuildConfig

/**
 * Default policy for screen-capture protection (`FLAG_SECURE`).
 *
 * Enforced in release builds (`!BuildConfig.DEBUG`) to keep credentials out of screenshots,
 * screen recordings and the recent-apps thumbnail, while remaining off in debug builds so
 * local debugging and virtual-display previews (such as the AI Studio Streaming Emulator)
 * render normally instead of blanking the window.
 */
fun isScreenCaptureProtectionEnabled(isDebugBuild: Boolean = BuildConfig.DEBUG): Boolean =
    !isDebugBuild

/**
 * Returns the [SecureFlagPolicy] to pass to `DialogProperties` for dialogs that accept or display
 * credentials. Resolves to [SecureFlagPolicy.SecureOn] when [enabled] is `true` (release builds)
 * and [SecureFlagPolicy.Inherit] when `false` (debug builds).
 */
fun secureDialogFlagPolicy(
    enabled: Boolean = isScreenCaptureProtectionEnabled()
): SecureFlagPolicy = if (enabled) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit

/**
 * Marks the host window as secure while this composable is on screen when [enabled] is `true`
 * (release builds by default), so its content (for example a Personal Access Token being typed)
 * is excluded from screenshots, screen recordings and the recent-apps thumbnail. The flag is
 * removed again when the composable leaves.
 *
 * Dialogs have their own window: protect those with
 * `DialogProperties(securePolicy = secureDialogFlagPolicy())` instead.
 */
@Composable
fun SecureScreenEffect(enabled: Boolean = isScreenCaptureProtectionEnabled()) {
    val context = LocalContext.current
    DisposableEffect(context, enabled) {
        val window = if (enabled) context.findActivity()?.window else null
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
