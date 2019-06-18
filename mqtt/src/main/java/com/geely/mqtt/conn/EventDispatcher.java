package com.geely.mqtt.conn;

import java.util.ArrayList;
import java.util.List;

/*
 * @author: luckyShane
 */
public class EventDispatcher {
    private final List<IConnStateListener> connStateListeners = new ArrayList<>();

    private EventDispatcher(){}

    public static EventDispatcher newInstance() {
        return new EventDispatcher();
    }

    public void registerConnStateListener(IConnStateListener connStateListener) {
        if (connStateListener != null) {
            synchronized (connStateListeners) {
                if (!connStateListeners.contains(connStateListener)) {
                    connStateListeners.add(connStateListener);
                }
            }
        }
    }

    public void unregisterConnStateListener(IConnStateListener connStateListener) {
        if (connStateListener != null) {
            synchronized (connStateListeners) {
                connStateListeners.remove(connStateListener);
            }
        }
    }

    public void notifyConnState(ConnectState connectState, String msg) {
        synchronized (connStateListeners) {
            for (IConnStateListener listener : connStateListeners) {
                switch (connectState) {
                    case CONNECTED:
                        listener.onConnected(false);
                        break;
                    case RECONNECTED:
                        listener.onConnected(true);
                        break;
                    case DISCONNECTED:
                        listener.onDisconnected();
                        break;
                    case CONNECT_FAIL:
                        listener.onConnectFail(msg);
                        break;
                }
            }
        }
    }

}
