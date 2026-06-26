package org.koitharu.kotatsu.core.ui.util

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import org.koitharu.kotatsu.core.ui.DefaultActivityLifecycleCallbacks
import java.util.WeakHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActivityRecreationHandle @Inject constructor() : DefaultActivityLifecycleCallbacks {

	private val activities = WeakHashMap<Activity, Unit>()

	override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
		activities[activity] = Unit
	}

	override fun onActivityDestroyed(activity: Activity) {
		activities.remove(activity)
	}

	fun recreateAll() {
		beginAnimatedRecreate()
		val snapshot = activities.keys.toList()
		snapshot.forEach { ActivityCompat.recreate(it) }
	}

	fun recreate(cls: Class<out Activity>) {
		val activity = activities.keys.find { x -> x.javaClass == cls } ?: return
		beginAnimatedRecreate()
		ActivityCompat.recreate(activity)
	}

	/**
	 * Returns true while a programmatic recreation (theme/colour change) is in progress.
	 * Other components (e.g. [AppProtectHelper]) can check this to avoid false-positive
	 * background-detection that would wrongly trigger the lock screen.
	 */
	val isRecreating: Boolean
		get() = isAnimatedRecreateInProgress

	/**
	 * Flag the next few activity (re)creations as theme-driven so they fade their content in
	 * instead of popping. An in-place [ActivityCompat.recreate] has no enter transition, so the
	 * fresh toolbar otherwise visibly settles (back button + title reflow); the fade masks it.
	 */
	private fun beginAnimatedRecreate() {
		isAnimatedRecreateInProgress = true
		mainHandler.removeCallbacks(clearAnimatedRecreate)
		mainHandler.postDelayed(clearAnimatedRecreate, ANIMATED_RECREATE_WINDOW_MS)
	}

	companion object {

		// Extend the window a bit beyond the animation window so that any lingering
		// onActivityStarted callbacks fired by the recreated activity are still covered.
		private const val ANIMATED_RECREATE_WINDOW_MS = 1_500L
		private val mainHandler = Handler(Looper.getMainLooper())
		private val clearAnimatedRecreate = Runnable { isAnimatedRecreateInProgress = false }

		/** True while activities recreated here should fade their content in (theme/colour change). */
		@JvmStatic
		@Volatile
		var isAnimatedRecreateInProgress = false
			private set
	}
}
