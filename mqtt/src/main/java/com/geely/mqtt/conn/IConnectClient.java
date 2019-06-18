package com.geely.mqtt.conn;

import android.content.Context;

/*
 * @author: luckyShane
 */
public interface IConnectClient {

    void startConnect(Context context, MqttConfigure configure);

    void disconnect();

    void registerConnStateListener(IConnStateListener connStateListener);

    void unregisterConnStateListener(IConnStateListener connStateListener);

    void addMessageCallback(MessageCallBack callBack);

    void removeMessageCallback(MessageCallBack callBack);

    void subscribe(String topic, int qos);

    void subscribe(String[] topic, int[] qos);

    void unsubscribe(String topic);

    void unsubscribe(String[] topics);

    void publish(String topic, String msg, IActionCallBack callBack);

    void publish(String topic, byte[] payload, int qos, boolean isRetain, IActionCallBack callBack);

    ConnectState getConnectState();

}
