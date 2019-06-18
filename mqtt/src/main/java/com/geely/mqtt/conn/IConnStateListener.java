package com.geely.mqtt.conn;

/*
 * @author: luckyShane
 */
public interface IConnStateListener {
    void onConnected(boolean isReconnect);
    void onDisconnected();
    void onConnectFail(String msg);
}
