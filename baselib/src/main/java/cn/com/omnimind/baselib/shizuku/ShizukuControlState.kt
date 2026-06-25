package cn.com.omnimind.baselib.shizuku

import android.content.Context
import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Which low-level technique physically performed the most recent device action.
 * This is the "hand" layer (how a tap/scroll/keystroke is delivered), NOT the
 * "brain" layer (Computer Use vs legacy, which is decided by the model binding).
 */
enum class ControlMethod {
    SHIZUKU,
    ACCESSIBILITY
}

/**
 * Central state for the optional "Shizuku power mode".
 *
 * - Persists the user's "prefer Shizuku control" toggle (mirrored with the
 *   Flutter shared_preferences store so both the Dart UI and the native
 *   executor read the same value).
 * - Tracks, at runtime, which technique (Shizuku vs Accessibility) last drove
 *   the phone, so the floating operation window can show a live indicator.
 */
object ShizukuControlState {

    private const val TAG = "[ShizukuControlState]"

    // Raw key. The Flutter shared_preferences plugin stores it as
    // "flutter.prefer_shizuku_control" inside FlutterSharedPreferences.
    const val PREF_KEY = "prefer_shizuku_control"
    private const val NATIVE_PREFS = "OmnibotSettings"
    private const val FLUTTER_PREFS = "FlutterSharedPreferences"
    private const val FLUTTER_KEY = "flutter.$PREF_KEY"

    private val _activeMethod = MutableStateFlow<ControlMethod?>(null)

    /** Last technique that actually executed a device action, or null when idle. */
    val activeMethod: StateFlow<ControlMethod?> = _activeMethod

    /** Reported by the device executor after each action. */
    fun reportMethod(method: ControlMethod) {
        _activeMethod.value = method
    }

    /** Clear the live indicator (e.g. when a task ends). */
    fun clearMethod() {
        _activeMethod.value = null
    }

    /** Whether the user opted into routing taps/keys through Shizuku. Defaults to off. */
    fun isPreferShizuku(context: Context): Boolean {
        return try {
            val native = context.getSharedPreferences(NATIVE_PREFS, Context.MODE_PRIVATE)
            if (native.contains(PREF_KEY)) return native.getBoolean(PREF_KEY, false)
            context.getSharedPreferences(FLUTTER_PREFS, Context.MODE_PRIVATE)
                .getBoolean(FLUTTER_KEY, false)
        } catch (e: Exception) {
            OmniLog.e(TAG, "read prefer-shizuku failed: ${e.message}")
            false
        }
    }

    /** Persist the toggle natively (Flutter side persists via shared_preferences). */
    fun setPreferShizuku(context: Context, enabled: Boolean): Boolean {
        return try {
            context.getSharedPreferences(NATIVE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_KEY, enabled)
                .commit()
        } catch (e: Exception) {
            OmniLog.e(TAG, "write prefer-shizuku failed: ${e.message}")
            false
        }
    }

    /**
     * Power mode is active only when the user enabled it AND Shizuku is actually
     * granted/connected. If Shizuku isn't ready we silently stay on accessibility.
     */
    fun isShizukuPowerModeActive(context: Context): Boolean {
        if (!isPreferShizuku(context)) return false
        return try {
            ShizukuCapabilityManager.get(context).isGranted()
        } catch (e: Exception) {
            OmniLog.e(TAG, "shizuku granted check failed: ${e.message}")
            false
        }
    }
}
