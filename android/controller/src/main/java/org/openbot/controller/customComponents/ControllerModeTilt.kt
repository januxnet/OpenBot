/*
 * Developed for the OpenBot project (https://openbot.org) by:
 *
 * Ivo Zivkov
 * izivkov@gmail.com
 *
 * Date: 2020-12-27, 10:57 p.m.
 */

package org.openbot.controller.customComponents

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import org.openbot.controller.utils.LocalEventBus
import org.openbot.controller.utils.SensorReader

class ControllerModeTilt @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ButtonWithBorder(context, attrs, defStyleAttr) {

    init {
        SensorReader.init(context)
        offState()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                onState()

                val event: LocalEventBus.ProgressEvents =
                    LocalEventBus.ProgressEvents.TiltControl
                LocalEventBus.onNext(event)
            }

            MotionEvent.ACTION_UP -> {
                offState()
            }
        }
        return true
    }
}
