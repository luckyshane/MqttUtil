package com.geely.mqtt.conn;

/*
 * @author: luckyShane
 */
public enum ConnectState {
    CONNECTED,
    RECONNECTED,
    DISCONNECTED,
    CONNECTING,
    CONNECT_FAIL;
}
