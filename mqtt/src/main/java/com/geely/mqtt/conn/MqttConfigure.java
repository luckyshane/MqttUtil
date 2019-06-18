package com.geely.mqtt.conn;


import javax.net.SocketFactory;

/*
 * @author: luckyShane
 */
public class MqttConfigure {
    public String userName;
    public String password;
    public String serverUrl;
    public String clientId;
    public SocketFactory socketFactory;
    public boolean isCleanSession = false;
    private int keepAliveInterval = 20;
    private int connTimeOutInSec = 60;

    public int getKeepAliveInterval() {
        return keepAliveInterval;
    }

    public void setKeepAliveInterval(int interval) {
        if (interval >=0) {
            keepAliveInterval = interval;
        }
    }

    public int getConnTimeOutInSec() {
        return connTimeOutInSec;
    }

    public void setConnTimeOutInSec(int connTimeOutInSec) {
        if (connTimeOutInSec >= 10) {
            this.connTimeOutInSec = connTimeOutInSec;
        }
    }

}
