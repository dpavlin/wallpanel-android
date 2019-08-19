/*
 * Copyright (c) 2019 ThanksMister LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.thanksmister.iot.wallpanel.ui.views

import android.content.Context
import android.os.Handler
import android.util.AttributeSet
import android.util.TypedValue
import android.widget.RelativeLayout
import kotlinx.android.synthetic.main.dialog_screen_saver.view.*
import timber.log.Timber
import java.lang.IllegalArgumentException
import java.util.*
import java.util.concurrent.TimeUnit

class ScreenSaverView : RelativeLayout {

    private var timeHandler: Handler? = null
    private var saverContext: Context? = null
    private var parentWidth: Int = 0
    private var parentHeight: Int = 0

    val calendar: Calendar = Calendar.getInstance()
    var last_minute = -1; // invalid to start with

    private val timeRunnable = object : Runnable {
        override fun run() {

            val time = System.currentTimeMillis()

            val date = Date()
            screenSaverClock.text = "%2d:%02d:%02d".format(date.hours, date.minutes, date.seconds)

            if (date.minutes != last_minute) {
                last_minute = date.minutes;
                val width = screenSaverClockLayout.width
                val height = screenSaverClockLayout.height
                parentWidth = screenSaverView.width
                parentHeight = screenSaverView.height
                try {
                    if (width > 0 && height > 0 && parentWidth > 0 && parentHeight > 0) {
                        if(parentHeight - width > 0) {
                            val newX = Random().nextInt(parentWidth - width)
                            screenSaverClockLayout.x = newX.toFloat()
                        }
                        if(parentHeight - height > 0) {
                            val newY = Random().nextInt(parentHeight - height)
                            screenSaverClockLayout.y = newY.toFloat()
                        }
                    }
                } catch (e: IllegalArgumentException) {
                    Timber.e(e.message)
                }
            }


            val offset = 1000 - System.currentTimeMillis() - time
            timeHandler?.postDelayed(this, offset)
        }
    }

    constructor(context: Context) : super(context) {
        saverContext = context
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        saverContext = context
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        timeHandler?.removeCallbacks(timeRunnable)
    }

    fun init() {

    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        setClockViews()
        timeHandler = Handler()
        timeHandler?.postDelayed(timeRunnable, 10)
    }

    // setup clock size based on screen and weather settings
    private fun setClockViews() {
        val initialRegular = screenSaverClock.textSize
        screenSaverClock.setTextSize(TypedValue.COMPLEX_UNIT_PX, initialRegular + 100)
    }
}

