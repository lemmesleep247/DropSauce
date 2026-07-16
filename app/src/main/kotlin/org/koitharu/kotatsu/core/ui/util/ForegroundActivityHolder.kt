package org.koitharu.kotatsu.core.ui.util

import android.app.Activity
import org.koitharu.kotatsu.core.ui.DefaultActivityLifecycleCallbacks
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds a reference to the activity currently in the foreground,
 * supplying an active window context for background-running components (like auto-solvers).
 */
@Singleton
class ForegroundActivityHolder @Inject constructor() : DefaultActivityLifecycleCallbacks {

    private var activityRef: WeakReference<Activity>? = null

    val current: Activity?
        get() = activityRef?.get()

    override fun onActivityResumed(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (activityRef?.get() == activity) {
            activityRef = null
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activityRef?.get() == activity) {
            activityRef = null
        }
    }
}
