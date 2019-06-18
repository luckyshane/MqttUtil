package com.geely.mqtt.conn;


/*
 * @author: luckyShane
 */
public interface MessageCallBack {
    void onMessageArrived(String topic, String payload);
}
