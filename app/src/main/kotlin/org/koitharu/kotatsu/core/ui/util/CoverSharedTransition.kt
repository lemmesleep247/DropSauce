package org.koitharu.kotatsu.core.ui.util

import android.animation.TimeInterpolator
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.transition.ArcMotion
import android.transition.ChangeBounds
import android.transition.ChangeClipBounds
import android.transition.ChangeImageTransform
import android.transition.ChangeTransform
import android.transition.Fade
import android.transition.Transition
import android.transition.TransitionSet
import android.view.View
import android.view.ViewTreeObserver
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityOptionsCompat
import androidx.core.app.SharedElementCallback
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.google.android.material.motion.MotionUtils
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.isAnimationsEnabled
import org.koitharu.kotatsu.image.ui.CoverImageView
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import com.google.android.material.R as materialR

/**
 * Drives the "manga cover floats into the details screen" shared-element transition.
 *
 * The cover image is the only shared element: it travels (along a gentle arc) and scales from the
 * tapped list item - wherever it is on screen and whatever size it has - to its resting place on the
 * details page. The rest of the page (backdrop, info, pull-up sheet) just fades, mimicking the stock
 * activity open/close animation - so the cover does its own thing while the page behaves as it did
 * before this transition existed.
 *
 * Works for both [org.koitharu.kotatsu.details.ui.DetailsActivity] and
 * [org.koitharu.kotatsu.details.ui.DetailsClassicActivity], and is bidirectional - pressing back
 * animates the cover back to the list item it came from.
 */
object CoverSharedTransition {

	/** Shared [View.setTransitionName] used on both the list cover and the details cover. */
	const val NAME = "manga_cover"

	/** Intent extra signalling the details screen that it was opened with a cover transition. */
	const val EXTRA_ENABLED = "cover_transition"

	// The list cover currently carrying [NAME]. Tracked so we can clear it before tagging a new
	// one, otherwise recycled RecyclerView rows could end up with duplicate transition names.
	private var taggedCover: WeakReference<View>? = null

	/**
	 * Builds the [ActivityOptionsCompat] bundle that launches the details screen with a cover
	 * transition, or `null` when no animation should run (no cover, or animations are disabled).
	 *
	 * @param coverView the cover [View] inside the tapped list item.
	 */
	fun makeSceneOptions(activity: Activity, coverView: View?): Bundle? {
		if (coverView == null || !coverView.context.isAnimationsEnabled) {
			return null
		}
		// Avoid duplicate transition names lingering on recycled list items.
		taggedCover?.get()?.let { previous ->
			if (previous !== coverView) {
				previous.transitionName = null
			}
		}
		coverView.transitionName = NAME
		taggedCover = WeakReference(coverView)
		ActivityCompat.setExitSharedElementCallback(activity, CoverExitCallback(coverView))
		return ActivityOptionsCompat.makeSceneTransitionAnimation(activity, coverView, NAME).toBundle()
	}

	/**
	 * Configures the details [activity] as the destination of a cover transition. Releases the
	 * postponed enter transition the moment the cover is laid out with an image (usually instant from
	 * Coil's cache) so the cover starts moving as fast as possible after the tap.
	 */
	fun setup(activity: AppCompatActivity, cover: CoverImageView) {
		cover.transitionName = NAME
		activity.window.apply {
			// Let the cover start moving immediately on open...
			allowEnterTransitionOverlap = true
			// ...but on return, don't let the list reappear until the details content has fully gone.
			// Overlapping is what left a half-faded "ghost" of page 2 composited over the list.
			allowReturnTransitionOverlap = false
			sharedElementEnterTransition = buildCoverTransition(activity)
			sharedElementReturnTransition = buildCoverTransition(activity)
			enterTransition = buildContentEnterTransition(activity)
			returnTransition = buildContentReturnTransition(activity)
		}
		activity.supportPostponeEnterTransition()
		val started = AtomicBoolean(false)
		var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null
		val begin = Runnable {
			if (started.compareAndSet(false, true)) {
				preDrawListener?.let { cover.viewTreeObserver.removeOnPreDrawListener(it) }
				preDrawListener = null
				activity.supportStartPostponedEnterTransition()
			}
		}
		// Fast path: as soon as the (laid-out) cover has an image to draw, start moving.
		preDrawListener = ViewTreeObserver.OnPreDrawListener {
			if (cover.drawable != null) {
				begin.run()
			}
			true
		}
		cover.viewTreeObserver.addOnPreDrawListener(preDrawListener)
		// Also start once the image request resolves, in case it arrives after the first draw.
		cover.addImageRequestListener(object : ImageRequest.Listener {
			override fun onSuccess(request: ImageRequest, result: SuccessResult) = cover.post(begin).let { }
			override fun onError(request: ImageRequest, result: ErrorResult) = cover.post(begin).let { }
			override fun onCancel(request: ImageRequest) = cover.post(begin).let { }
		})
		// Safety net: never leave the screen postponed (and blank) if the cover is slow to load.
		cover.postDelayed(begin, RESUME_TIMEOUT_MS)
	}

	private fun buildCoverTransition(context: Context): Transition {
		// A shallow arc makes the travel feel natural instead of a straight A->B slide.
		val arc = ArcMotion().apply {
			minimumHorizontalAngle = 15f
			minimumVerticalAngle = 15f
			maximumAngle = 30f
		}
		val changeBounds = ChangeBounds().apply { pathMotion = arc }
		return TransitionSet().apply {
			ordering = TransitionSet.ORDERING_TOGETHER
			addTransition(changeBounds)
			addTransition(ChangeTransform())
			addTransition(ChangeClipBounds())
			addTransition(ChangeImageTransform())
			pathMotion = arc
			duration = context.motionDuration(materialR.attr.motionDurationLong1, DEFAULT_COVER_DURATION)
			interpolator = context.motionInterpolator(materialR.attr.motionEasingEmphasizedInterpolator)
		}
	}

	// The cover floats on its own (shared element); the rest of the page just fades, the way the
	// stock activity open/close animation does. Standard (utility) easing keeps it feeling like a
	// plain navigation rather than a flashy custom transition.
	private fun buildContentEnterTransition(context: Context): Transition = Fade().apply {
		duration = context.motionDuration(materialR.attr.motionDurationMedium3, DEFAULT_CONTENT_ENTER_DURATION)
		interpolator = context.motionInterpolator(materialR.attr.motionEasingStandardDecelerateInterpolator)
		excludeContentStragglers()
	}

	private fun buildContentReturnTransition(context: Context): Transition = Fade().apply {
		duration = context.motionDuration(materialR.attr.motionDurationMedium2, DEFAULT_CONTENT_RETURN_DURATION)
		interpolator = context.motionInterpolator(materialR.attr.motionEasingStandardAccelerateInterpolator)
		// On the way back, every content view MUST fade out with the page. Excluding the "more" button
		// and reading-progress views here (as the enter transition does) left them at full alpha while
		// the rest of page 2 faded, so they lingered on top and looked like they bled onto the list.
		excludeSystemBars()
	}

	private fun Transition.excludeSystemBars() {
		// Keep the system bars steady while the content fades.
		excludeTarget(android.R.id.statusBarBackground, true)
		excludeTarget(android.R.id.navigationBarBackground, true)
	}

	private fun Transition.excludeContentStragglers() {
		excludeSystemBars()
		// On enter, the "more" button and the reading-progress views have their visibility flipped by
		// layout/draw listeners on the details screen, so keep the fade's hands off them to avoid an
		// awkward pop-in - they just appear with the window instead.
		excludeTarget(R.id.button_description_more, true)
		excludeTarget(R.id.progress, true)
		excludeTarget(R.id.textView_progress, true)
		excludeTarget(R.id.textView_progress_label, true)
		// The frosted detail-box blur has its alpha set asynchronously (once the backdrop bitmap loads),
		// so the fade would capture/restore it at alpha 0 and leave the box with no background or blur.
		excludeTarget(R.id.image_box_blur, true)
	}

	private fun Context.motionDuration(attrResId: Int, default: Int): Long =
		MotionUtils.resolveThemeDuration(this, attrResId, default).toLong()

	private fun Context.motionInterpolator(attrResId: Int): TimeInterpolator =
		MotionUtils.resolveThemeInterpolator(this, attrResId, FastOutSlowInInterpolator())

	/**
	 * Re-maps the shared cover to the current list item on return, so the cover animates back to the
	 * right place even if the list scrolled or the row was recycled. Falls back to a plain fade when
	 * the original cover is no longer on screen.
	 */
	private class CoverExitCallback(coverView: View) : SharedElementCallback() {

		private val coverRef = WeakReference(coverView)

		override fun onMapSharedElements(
			names: MutableList<String>,
			sharedElements: MutableMap<String, View>,
		) {
			val cover = coverRef.get()
			if (cover != null && cover.isAttachedToWindow && cover.isShown) {
				sharedElements[NAME] = cover
			} else {
				sharedElements.remove(NAME)
				names.remove(NAME)
			}
		}
	}

	private const val DEFAULT_COVER_DURATION = 450
	private const val DEFAULT_CONTENT_ENTER_DURATION = 350
	private const val DEFAULT_CONTENT_RETURN_DURATION = 300
	private const val RESUME_TIMEOUT_MS = 200L
}
