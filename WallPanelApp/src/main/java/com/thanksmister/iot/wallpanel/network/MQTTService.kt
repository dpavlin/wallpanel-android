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

package com.thanksmister.iot.wallpanel.network

import android.R.id.message
import android.content.Context
import android.text.TextUtils
import com.crashlytics.android.Crashlytics
import com.thanksmister.iot.wallpanel.BuildConfig
import com.thanksmister.iot.wallpanel.R
import com.thanksmister.iot.wallpanel.utils.MqttUtils
import com.thanksmister.iot.wallpanel.utils.StringUtils
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import timber.log.Timber
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.NoSuchAlgorithmException
import java.security.spec.InvalidKeySpecException
import java.util.concurrent.atomic.AtomicBoolean

class MQTTService(private var context: Context, options: MQTTOptions,
                  private var listener: MqttManagerListener?) : MQTTServiceInterface {

    private var mqttClient: MqttAndroidClient? = null
    private var mqttOptions: MQTTOptions? = null

    init {
        initialize(options)
    }

    override fun reconfigure(context: Context,
                             newOptions: MQTTOptions,
                             listener: MqttManagerListener) {
        try {
            close()
        } catch (e: MqttException) {
            // empty
        }
        this.listener = listener
        this.context = context
        initialize(newOptions)
    }

    interface MqttManagerListener {
        fun subscriptionMessage(id: String, topic: String, payload: String)
        fun handleMqttException(errorMessage: String)
        fun handleMqttDisconnected()
        fun handleMqttConnected()
    }

    @Throws(MqttException::class)
    override fun close() {
        Timber.d("close")
        mqttClient?.let {
            it.setCallback(null)
            if (it.isConnected) {
                it.disconnect(0)
            }
        }
    }

    override fun publish(command: String, payload: String) {
        try {
            if (mqttClient != null && !mqttClient!!.isConnected) {
                // if for some reason the mqtt client has disconnected, we should try to connect
                // it again.
                try {
                    initializeMqttClient()
                } catch (e: MqttException) {
                    listener?.handleMqttException("Could not initialize MQTT")
                } catch (e: IOException) {
                    listener?.handleMqttException("Could not initialize MQTT")
                } catch (e: GeneralSecurityException) {
                    listener?.handleMqttException("Could not initialize MQTT")
                }
            }
            // TODO append the "command" part
            Timber.d("Publishing: $payload")
            Timber.d("Base Topic: ${mqttOptions?.getBaseTopic()}")
            Timber.d("Command Topic: $command")
            Timber.d("Payload: $payload")
            val mqttMessage = MqttMessage()
            mqttMessage.payload = payload.toByteArray()
            mqttMessage.isRetained = SHOULD_RETAIN
            sendMessage(mqttOptions?.getBaseTopic() + command, mqttMessage)

        } catch (e: MqttException) {
            listener?.handleMqttException("Exception while publishing command $command and it's payload to the MQTT broker.")
        }

    }

    /**
     * Initialize a Cloud IoT Endpoint given a set of configuration options.
     * @param options Cloud IoT configuration options.
     */
    private fun initialize(options: MQTTOptions) {
        Timber.d("initialize")
        try {
            mqttOptions = options
            Timber.i("Service Configuration:")
            Timber.i("Client ID: " + mqttOptions!!.getClientId())
            Timber.i("Username: " + mqttOptions!!.getUsername())
            Timber.i("Password: " + mqttOptions!!.getPassword())
            Timber.i("TslConnect: " + mqttOptions!!.getTlsConnection())
            Timber.i("MQTT Configuration:")
            Timber.i("Broker: " + mqttOptions?.brokerUrl)
            Timber.i("Subscribed to state topics: " + StringUtils.convertArrayToString(mqttOptions!!.getStateTopics()))
            Timber.i("Publishing to base topic: " + mqttOptions!!.getBaseTopic())
            if (mqttOptions!!.isValid) {
                initializeMqttClient()
            } else {
                listener?.handleMqttDisconnected()
            }
        } catch (e: MqttException) {
            listener?.handleMqttException(context.getString(R.string.error_mqtt_connection))
        } catch (e: IOException) {
            listener?.handleMqttException(context.getString(R.string.error_mqtt_connection))
        } catch (e: GeneralSecurityException) {
            listener?.handleMqttException(context.getString(R.string.error_mqtt_connection))
        }
    }

    @Throws(MqttException::class, IOException::class, NoSuchAlgorithmException::class, InvalidKeySpecException::class)
    private fun initializeMqttClient() {
        Timber.d("initializeMqttClient")
        try {
            mqttOptions?.let { mqttOptions ->
                mqttClient = MqttAndroidClient(context, mqttOptions.brokerUrl, mqttOptions.getClientId(), MemoryPersistence())
                mqttClient?.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        subscribeToTopics(mqttOptions.getStateTopics())
                    }

                    override fun connectionLost(cause: Throwable?) {}
                    override fun messageArrived(topic: String?, message: MqttMessage?) {}
                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })

                val options = MqttConnectOptions()
                options.isAutomaticReconnect = true
                options.isCleanSession = false
                if (!TextUtils.isEmpty(mqttOptions.getUsername()) && !TextUtils.isEmpty(mqttOptions.getPassword())) {
                    options.userName = mqttOptions.getUsername()
                    options.password = mqttOptions.getPassword().toCharArray()
                }

                try {
                    mqttClient?.connect(options, null, object : IMqttActionListener {
                        override fun onSuccess(asyncActionToken: IMqttToken) {
                            val disconnectedBufferOptions = DisconnectedBufferOptions()
                            disconnectedBufferOptions.isBufferEnabled = true
                            disconnectedBufferOptions.bufferSize = 100
                            disconnectedBufferOptions.isPersistBuffer = false
                            disconnectedBufferOptions.isDeleteOldestMessages = false
                            mqttClient?.setBufferOpts(disconnectedBufferOptions)
                            listener?.handleMqttConnected()
                        }

                        override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                            Timber.e("Failed to connect to: " + mqttOptions.brokerUrl + " exception: " + exception)
                            listener?.handleMqttException("Error establishing MQTT connection to MQTT broker with address ${mqttOptions?.brokerUrl}.")
                        }
                    })
                } catch (e: NullPointerException) {
                    Timber.e(e, e.message)
                } catch (e: MqttException) {
                    listener?.handleMqttException("Error establishing MQTT connection to MQTT broker with address ${mqttOptions?.brokerUrl}.")
                }
            }
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
        } catch (e: NullPointerException) {
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
            listener?.handleMqttException("Error establishing MQTT connection to MQTT broker with address ${mqttOptions?.brokerUrl}.")
        }
    }

    @Throws(MqttException::class)
    private fun sendMessage(mqttTopic: String?, mqttMessage: MqttMessage) {
        Timber.d("sendMessage")
        try {
            mqttClient?.let {
                if (it.isConnected) {
                    it.publish(mqttTopic, mqttMessage)
                    Timber.d("Command Topic: $mqttTopic Payload: $message")
                }
            }
        } catch (e: NullPointerException) {
            Timber.e(e.message)
        } catch (e: MqttException) {
            Timber.e("Error Sending Command: " + e.message)
            listener?.handleMqttException("Couldn't send message to the MQTT broker for topic $mqttTopic, check the MQTT client settings or your connection to the broker.")
        }
    }

    private fun subscribeToTopics(topicFilters: Array<String>?) {
        Timber.d("Subscribe to Topics: " + StringUtils.convertArrayToString(topicFilters))
        try {
            mqttClient?.let {
                if (it.isConnected) {
                    it.subscribe(topicFilters, MqttUtils.getQos(topicFilters!!.size),
                            MqttUtils.getMqttMessageListeners(topicFilters.size, listener))
                }
            }
        } catch (e: NullPointerException) {
            if (!BuildConfig.DEBUG) {
                Crashlytics.logException(e)
            }
        } catch (e: MqttException) {
            if (!BuildConfig.DEBUG) {
                Crashlytics.logException(e)
            }
            listener?.handleMqttException("Exception while subscribing to topics [${topicFilters.toString()}], the connection to the MQTT broker could have been disconnected.")
        }
    }

    companion object {
        // TODO make this optional
        private val SHOULD_RETAIN = false
    }
}