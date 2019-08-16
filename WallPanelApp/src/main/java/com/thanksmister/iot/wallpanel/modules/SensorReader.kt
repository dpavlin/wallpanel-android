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

package com.thanksmister.iot.wallpanel.modules

import android.content.Context
import android.content.Context.SENSOR_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.Sensor.*
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Handler
import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.util.*
import javax.inject.Inject

class SensorReader @Inject
constructor(private val context: Context) {

    private val mSensorManager: SensorManager?
    private val mSensorList = ArrayList<Sensor>()
    private val batteryHandler = Handler()
    private var updateFrequencyMilliSeconds: Int = 0
    private var callback: SensorCallback? = null
    private var sensorsPublished: Boolean = false
    private var lightSensorEvent: SensorEvent? = null
    private val lastSensorEvent = mutableMapOf<String, Long?>()

    private val batteryHandlerRunnable = object : Runnable {
        override fun run() {
            if (updateFrequencyMilliSeconds > 0) {
                Timber.d("Updating Battery")
                getBatteryReading()
                batteryHandler.postDelayed(this, updateFrequencyMilliSeconds.toLong())
                sensorsPublished = false
            }
        }
    }

    init {
        Timber.d("Creating SensorReader")
        mSensorManager = context.getSystemService(SENSOR_SERVICE) as SensorManager
        for (s in mSensorManager.getSensorList(Sensor.TYPE_ALL)) {
            Log.i("TAG", s.toString())
            //if (getSensorName(s.type) != null)
            mSensorList.add(s)
        }
    }

    fun startReadings(freqSeconds: Int, callback: SensorCallback) {
        Timber.d("startReadings")
        this.callback = callback
        if (freqSeconds >= 0) {
            updateFrequencyMilliSeconds = 1000 * freqSeconds
            batteryHandler.postDelayed(batteryHandlerRunnable, updateFrequencyMilliSeconds.toLong())
            startSensorReadings()
        }
    }

    fun stopReadings() {
        Timber.d("stopReadings")
        batteryHandler.removeCallbacksAndMessages(batteryHandlerRunnable)
        updateFrequencyMilliSeconds = 0
        stopSensorReading()
    }

    private fun publishSensorData(sensorName: String, sensorData: JSONObject) {
        Timber.d("publishSensorData")
        callback?.publishSensorData(sensorName, sensorData)
    }

    private fun getSensorName(sensorType: Int): String {
        return when (sensorType) {
            TYPE_ACCELEROMETER -> "accelerometer"

            TYPE_AMBIENT_TEMPERATURE -> "ambient_temperature"
            TYPE_LIGHT -> "light"
            TYPE_MAGNETIC_FIELD -> "magnetic_field"
            TYPE_PRESSURE -> "pressure"
            TYPE_RELATIVE_HUMIDITY -> "humidity"
            TYPE_TEMPERATURE -> "temperature"
            else -> sensorType.toString()
        }
    }

    /*
    private fun getSensorUnit(sensorType: Int): String? {
        when (sensorType) {
            Sensor.TYPE_AMBIENT_TEMPERATURE -> return UNIT_C
            Sensor.TYPE_LIGHT -> return UNIT_LX
            Sensor.TYPE_MAGNETIC_FIELD -> return UNIT_UT
            Sensor.TYPE_PRESSURE -> return UNIT_HPA
            Sensor.TYPE_RELATIVE_HUMIDITY -> return UNIT_PERCENTAGE
        }
    }
    */

    /**
     * Start all sensor readings.
     */
    private fun startSensorReadings() {
        Timber.d("startSensorReadings")
        if (mSensorManager != null) {
            for (sensor in mSensorList) {
                mSensorManager.registerListener(sensorListener, sensor, updateFrequencyMilliSeconds)
                Timber.i("SENSOR name: ${sensor.name} vendor: ${sensor.vendor} minDelay=${sensor.minDelay} power=${sensor.power}")
            }
        }
    }

    /**
     * Stop all sensor readings.
     */
    private fun stopSensorReading() {
        Timber.d("stopSensorReading")
        for (sensor in mSensorList) {
            mSensorManager?.unregisterListener(sensorListener, sensor)
        }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            //if(event != null && !sensorsPublished) {
            if (event != null) {
                val t = lastSensorEvent.get(event.sensor.name)
                val time = System.currentTimeMillis()
                if (t == null || (time - t) > updateFrequencyMilliSeconds) {
                    lastSensorEvent.put(event.sensor.name, time)
                    if (t != null) Log.i("XXX-update", "dt=" + (time - t)
                            + " [" + event.sensor.name + "] " + event.sensor.type)
                } else {
                    return
                }
                //Log.i("XXX","timestamp="+event.timestamp.toLong()+ " id="+ event.sensor.id.toInt()+" event.values="+ event.values.size.toString() + event.values[0].toString())
                //Log.i("XXX","event="+event.toString())
                val data = JSONObject()

                //data.put(VALUE, event.values[0])
                data.put("timestamp", event.timestamp)
                data.put("time", System.currentTimeMillis().toLong())
                data.put("name", event.sensor.name)
                data.put("vendor", event.sensor.vendor)
                val sensorTypeName = getSensorName(event.sensor.type)
                data.put("type", sensorTypeName)
                data.put("type_id", event.sensor.type)

                var max_values = when (event.sensor.type) {
                    /*
                     Table 1. Environment sensors that are supported on the Android platform.
Sensor 	Sensor event data 	Units of measure 	Data description
TYPE_AMBIENT_TEMPERATURE 	event.values[0] 	°C 	Ambient air temperature.
TYPE_LIGHT 	event.values[0] 	lx 	Illuminance.
TYPE_PRESSURE 	event.values[0] 	hPa or mbar 	Ambient air pressure.
TYPE_RELATIVE_HUMIDITY 	event.values[0] 	% 	Ambient relative humidity.
TYPE_TEMPERATURE 	event.values[0] 	°C 	Device temperature.1


                     */
                    TYPE_AMBIENT_TEMPERATURE, TYPE_LIGHT, TYPE_PRESSURE,
                    TYPE_RELATIVE_HUMIDITY, TYPE_TEMPERATURE -> 1
                    else -> event.values.size
                }

                if (max_values == 1) {
                    data.put(VALUE, event.values[0])
                } else if (event.sensor.type == TYPE_ACCELEROMETER) {
                    data.put("x",event.values[0])
                    data.put("y",event.values[1])
                    data.put("z",event.values[2])
                } else {
                    for (i in 0 until max_values) {
                        data.put(String.format("v%d", i), event.values[i])
                    }
                }
                //data.put(UNIT, getSensorUnit(event.sensor.type))
                //data.put(ID, event.sensor.name)
                //publishSensorData(event.sensor.stringType.replace("android.sensor.",""), data)
                publishSensorData(sensorTypeName, data)
                sensorsPublished = true
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        }
    }

    // TODO let's move this to its own setting
    private fun getBatteryReading() {
        Timber.d("getBatteryReading")
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, intentFilter)
        val batteryStatusIntExtra = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                ?: -1
        val chargePlug = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val data = JSONObject()
        try {
            data.put(VALUE, level)
            data.put(UNIT, "%")
            data.put("charging", batteryStatusIntExtra == BatteryManager.BATTERY_STATUS_CHARGING)
            data.put("full", batteryStatusIntExtra == BatteryManager.BATTERY_STATUS_FULL)
            data.put("acPlugged", chargePlug == BatteryManager.BATTERY_PLUGGED_AC)
            data.put("usbPlugged", chargePlug == BatteryManager.BATTERY_PLUGGED_USB)
            data.put("time", System.currentTimeMillis().toLong())
        } catch (ex: JSONException) {
            ex.printStackTrace()
        }

        publishSensorData("battery", data)
    }

    companion object {
        const val UNIT_C: String = "°C"
        const val UNIT_HPA: String = "hPa"
        const val UNIT_UT: String = "uT"
        const val UNIT_LX: String = "lx"
        const val VALUE = "value"
        const val UNIT = "unit"
        const val ID = "id"
    }
}
