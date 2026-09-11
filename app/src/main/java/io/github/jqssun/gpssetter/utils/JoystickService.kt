package io.github.jqssun.gpssetter.utils

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.*
import io.github.controlwear.virtual.joystick.android.JoystickView
import io.github.jqssun.gpssetter.R
import kotlin.math.cos
import kotlin.math.sin

class JoystickService : Service() {

    private var wm: WindowManager? = null
    private var mJoystickContainerView: View? = null
    private var mJoystickView: JoystickView? = null
    private var mJoystickLayoutParams: WindowManager.LayoutParams? = null
    private var lat : Double = PrefManager.getLat
    private var lon : Double = PrefManager.getLng

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        isRunning = true
        wm =  getSystemService(WINDOW_SERVICE) as WindowManager
        val mInflater :LayoutInflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        mJoystickContainerView = mInflater.inflate(R.layout.joystick, null as ViewGroup?) as View
        mJoystickView = mJoystickContainerView!!.findViewById(R.id.joystickView_right)
        mJoystickView?.setOnTouchListener { v, event ->
            if (event.action == 1){
                try {
                    lat = PrefManager.getLat
                    lon = PrefManager.getLng
                    updateLocation(lat, lon)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

            }
            false
        }
        // mJoystickView?.setOnMoveListener { angle, strength ->
        mJoystickView?.setOnMoveListener { angle, strength, event ->
            val radians = Math.toRadians(angle.toDouble())
            try {
                // strength is an Int (0-100); dividing by an Int truncated to 0 below 30 and
                // stepped coarsely above, so the stick felt dead. Use a Double so the push
                // scales smoothly.
                val factorX: Double = cos(radians) / 100000.0 * (strength / 30.0)
                val factorY: Double = sin(radians) / 100000.0 * (strength / 30.0)
                lon = PrefManager.getLng + factorX
                lat = PrefManager.getLat + factorY
                updateLocation(lat, lon)

            }catch (e : Exception){
                e.printStackTrace()
            }
        }
        mJoystickLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        mJoystickLayoutParams?.let {
            it.gravity = Gravity.LEFT
        }

        wm!!.addView(mJoystickContainerView,mJoystickLayoutParams)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        if (this.mJoystickContainerView != null) {
            this.wm!!.removeView(mJoystickContainerView);
            this.mJoystickContainerView = null;
        }
    }

    companion object {
        /** Whether the joystick overlay is currently up (avoids the deprecated getRunningServices). */
        @Volatile
        var isRunning = false
            private set
    }

    private fun updateLocation(lat : Double,lon : Double){
        PrefManager.update(start = PrefManager.isStarted, la = lat, ln = lon)

    }

}