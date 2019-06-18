package com.geely.mqtt.conn;

import android.content.Context;
import android.text.TextUtils;

import com.blankj.utilcode.util.LogUtils;
import com.geely.mqtt.Util;

import org.eclipse.paho.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/*
 * @author: luckyShane
 */
public class ConnectClient implements IConnectClient {
    private ConnectState connectState;
    private MqttAndroidClient client;
    private MqttConnectOptions options;
    private EventDispatcher eventDispatcher = EventDispatcher.newInstance();
    private List<MessageCallBack> messageCallBacks = new ArrayList<>();
    private Map<String, Integer> pendingTopicsToSubscribe = new HashMap<>();
    private Map<String, Integer> currentSubscribingTopics = new HashMap<>();
    private Map<String, Integer> subscribedTopics = new HashMap<>();
    private SubscribeThread subscribeThread;
    private final Object SUBSCRIBE_LOCK = new Object();

    public ConnectClient() {
        this.connectState = ConnectState.DISCONNECTED;
    }

    @Override
    public void startConnect(Context context, MqttConfigure configure) {
        context = context.getApplicationContext();
        if (client != null && (connectState == ConnectState.CONNECTED || connectState == ConnectState.CONNECTING)) {
            LogUtils.w("already been connecting or connected. ignore current connection");
            return;
        }
        if (client != null) {
            try {
                client.setCallback(null);
                client.disconnect();
            } catch (MqttException e) {
                e.printStackTrace();
            }
        }
        connectState = ConnectState.CONNECTING;
        client = new MqttAndroidClient(context, configure.serverUrl, configure.clientId);
        client.setTraceEnabled(true);
        client.setCallback(new ConnCallback());
        options = new MqttConnectOptions();
        options.setCleanSession(configure.isCleanSession);
        options.setAutomaticReconnect(true);
        options.setKeepAliveInterval(configure.getKeepAliveInterval());
        if (configure.socketFactory != null) {
            options.setSocketFactory(configure.socketFactory);
        }
        options.setUserName(configure.userName);
        if (configure.password != null) {
            options.setPassword(configure.password.toCharArray());
        }
        options.setConnectionTimeout(configure.getConnTimeOutInSec());
        try {
            client.connect(options, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    LogUtils.d("connect onSuccess");
                    connectState = ConnectState.CONNECTED;
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    LogUtils.e("startConnect -> connect -> onFailure: " + exception.getMessage());
                    exception.printStackTrace();
                    if (exception instanceof MqttException) {
                        // 网络状态变更的时候，内部自动重连可能会抛出这种异常，进行排除。
                        MqttException mqttException = (MqttException) exception;
                        if (mqttException.getReasonCode() == MqttException.REASON_CODE_CLIENT_CONNECTED) {
                            if (connectState != ConnectState.CONNECTED) {
                                connectState = ConnectState.CONNECTED;
                                getDispatcher().notifyConnState(ConnectState.CONNECTED, "");
                            }
                            return;
                        } else if (mqttException.getReasonCode() == MqttException.REASON_CODE_CONNECT_IN_PROGRESS) {
                            if (connectState != ConnectState.CONNECTING) {
                                connectState = ConnectState.CONNECTING;
                                getDispatcher().notifyConnState(ConnectState.CONNECTING, "");
                            }
                            return;
                        }
                    }
                    connectState = ConnectState.CONNECT_FAIL;
                    getDispatcher().notifyConnState(ConnectState.CONNECT_FAIL, "connect onFailure: " + exception.getMessage());
                }
            });
        } catch (MqttException e) {
            LogUtils.e("startConnect -> connect -> exception: " + e.getMessage());
            e.printStackTrace();
            if (e.getReasonCode() != MqttException.REASON_CODE_CLIENT_CONNECTED) {
                connectState = ConnectState.CONNECT_FAIL;
                getDispatcher().notifyConnState(ConnectState.CONNECT_FAIL, "connect exception: " + e.getMessage());
            }
        }
    }

    @Override
    public void disconnect() {
        stopSubscribe();
        if (client != null) {
            try {
                client.setCallback(null);
                client.disconnect();
            } catch (MqttException e) {
                LogUtils.w("disconnect error " + e.getMessage());
                e.printStackTrace();
            }
            client = null;
            connectState = ConnectState.DISCONNECTED;
            getDispatcher().notifyConnState(ConnectState.DISCONNECTED, "");
        }
    }

    @Override
    public void registerConnStateListener(IConnStateListener connStateListener) {
        getDispatcher().registerConnStateListener(connStateListener);
    }

    @Override
    public void unregisterConnStateListener(IConnStateListener connStateListener) {
        getDispatcher().unregisterConnStateListener(connStateListener);
    }

    @Override
    public ConnectState getConnectState() {
        return connectState;
    }

    @Override
    public void addMessageCallback(MessageCallBack callBack) {
        if (callBack != null && !messageCallBacks.contains(callBack)) {
            messageCallBacks.add(callBack);
        }
    }

    @Override
    public void removeMessageCallback(MessageCallBack callBack) {
        if (callBack != null) {
            messageCallBacks.remove(callBack);
        }
    }

    private class ConnCallback implements MqttCallbackExtended {

        @Override
        public void connectComplete(boolean reconnect, String serverURI) {
            LogUtils.d("connectComplete reconnect: " + reconnect + ", URI: " + serverURI);
            connectState = reconnect ? ConnectState.RECONNECTED : ConnectState.CONNECTED;
            getDispatcher().notifyConnState(connectState, "");
            if (!reconnect || options.isCleanSession()) {
                // 第一次连接或者是对于重连clean session的情况，需要重新订阅
                stopSubscribe();    // 断开订阅，并且充值已订阅的topic来保证后面所有的topic都能重新订阅
            }
            startSubscribeTask();
        }

        @Override
        public void connectionLost(Throwable cause) {
            String msg = "";
            if (cause != null) {
                msg = cause.getMessage();
                cause.printStackTrace();
            }
            LogUtils.w("connectionLost: " + msg);
            connectState = ConnectState.DISCONNECTED;
            getDispatcher().notifyConnState(ConnectState.DISCONNECTED, msg);
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            String content = new String(message.getPayload());
            LogUtils.i("messageArrived [" + topic + "]: " + content);
            notifyMessageArrived(topic, content);
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            LogUtils.i("deliveryComplete");
        }
    }

    @Override
    public void subscribe(final String topic, int qos) {
        Util.validateQos(qos);
        synchronized (SUBSCRIBE_LOCK) {
            if (pendingTopicsToSubscribe.containsKey(topic) || subscribedTopics.containsKey(topic)
                    || currentSubscribingTopics.containsKey(topic)) {
                LogUtils.w("topic [" + topic + "] is subscribing or already subscribed, so ignore");
                return;
            }
            pendingTopicsToSubscribe.put(topic, qos);
            SUBSCRIBE_LOCK.notify();
        }
    }

    private void doSubscribe(final String[] topic, int[] qos, final IActionCallBack actionCallBack) {
        if (client == null || !client.isConnected()) {
            actionCallBack.onError("client not connected");
            return;
        }
        try {
            client.subscribe(topic, qos, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    if (actionCallBack != null) {
                        actionCallBack.onSuccess();
                    }
                    LogUtils.d("订阅成功: ", topic);
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    if (actionCallBack != null) {
                        actionCallBack.onError(exception.getMessage());
                    }
                    LogUtils.e("订阅失败: " + exception.getMessage(), topic);
                }
            });
        } catch (MqttException e) {
            e.printStackTrace();
            actionCallBack.onError(e.getMessage());
        }
    }

    private void doUnsubscribe(final String[] topics, final IActionCallBack callBack) {
        if (client == null || !client.isConnected()) {
            callBack.onError("client not connected");
            return;
        }
        try {
            client.unsubscribe(topics);
            if (callBack != null) {
                callBack.onSuccess();
            }
        } catch (MqttException e) {
            e.printStackTrace();
            if (callBack != null) {
                callBack.onError(e.getMessage());
            }
        }
    }


    @Override
    public void subscribe(String[] topics, int[] qos) {
        int index = -1;
        synchronized (SUBSCRIBE_LOCK) {
            for (String topic : topics) {
                index++;
                if (pendingTopicsToSubscribe.containsKey(topic) || subscribedTopics.containsKey(topic)
                        || currentSubscribingTopics.containsKey(topic)) {
                    LogUtils.w("topic " + topic + " is subscribing or subscribed");
                    continue;
                }
                Util.validateQos(qos[index]);
                pendingTopicsToSubscribe.put(topic, qos[index]);
            }
            if (!pendingTopicsToSubscribe.isEmpty()) {
                SUBSCRIBE_LOCK.notify();
            }
        }
    }

    @Override
    public void unsubscribe(final String topic) {
        if (TextUtils.isEmpty(topic)) {
            return;
        }
        synchronized (SUBSCRIBE_LOCK) {
            currentSubscribingTopics.remove(topic);
            pendingTopicsToSubscribe.remove(topic);
            subscribedTopics.remove(topic);
        }
        doUnsubscribe(new String[]{topic}, new IActionCallBack() {
            @Override
            public void onSuccess() {
                LogUtils.d("取消订阅成功：", topic);
            }

            @Override
            public void onError(String msg) {
                LogUtils.e("取消订阅失败：" + msg, topic);
            }
        });
    }

    @Override
    public void unsubscribe(final String[] topics) {
        if (topics == null || topics.length <= 0) {
            return;
        }
        synchronized (SUBSCRIBE_LOCK) {
            for (String topic : topics) {
                pendingTopicsToSubscribe.remove(topic);
                currentSubscribingTopics.remove(topic);
                subscribedTopics.remove(topic);
            }
        }
        doUnsubscribe(topics, new IActionCallBack() {
            @Override
            public void onSuccess() {
                LogUtils.d("取消订阅成功：", topics);
            }

            @Override
            public void onError(String msg) {
                LogUtils.e("取消订阅失败：" + msg, topics);
            }
        });
    }

    @Override
    public void publish(String topic, String msg, IActionCallBack callBack) {
        byte[] payload = msg.getBytes();
        publish(topic, payload, 2, false, callBack);
    }


    @Override
    public void publish(String topic, byte[] msg, int qos, boolean isRetain, final IActionCallBack callback) {
        if (client != null && client.isConnected()) {
            try {
                client.publish(topic, msg, qos, isRetain, null, new IMqttActionListener() {
                    @Override
                    public void onSuccess(IMqttToken asyncActionToken) {
                        if (callback != null) {
                            callback.onSuccess();
                        }
                    }

                    @Override
                    public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                        if (callback != null) {
                            callback.onError("publish failed: " + exception.getMessage());
                        }
                    }
                });
            } catch (MqttException e) {
                e.printStackTrace();
                if (callback != null) {
                    callback.onError("publish exception: " + e.getMessage());
                }
            }
        } else {
            if (callback != null) {
                callback.onError("not connected");
            }
        }
    }

    private EventDispatcher getDispatcher() {
        return eventDispatcher;
    }

    private void notifyMessageArrived(String topic, String message) {
        for (MessageCallBack callBack : messageCallBacks) {
            callBack.onMessageArrived(topic, message);
        }
    }

    private void startSubscribeTask() {
        if (subscribeThread == null || subscribeThread.isCancelled()) {
            LogUtils.d("start subscribe task");
            subscribeThread = new SubscribeThread();
            subscribeThread.start();
        } else {
            LogUtils.w("subscribe task already started");
        }
    }

    /**
     * 停止订阅，并且将所有topic归类到未订阅，用于后期连接成功后再次进行订阅
     */
    private void stopSubscribe() {
        if (subscribeThread != null) {
            subscribeThread.cancel();
            subscribeThread = null;
        }
        synchronized (SUBSCRIBE_LOCK) {
            pendingTopicsToSubscribe.putAll(currentSubscribingTopics);
            pendingTopicsToSubscribe.putAll(subscribedTopics);
            currentSubscribingTopics.clear();
            subscribedTopics.clear();
        }
    }

    private class SubscribeThread extends Thread {
        private volatile boolean cancelled;

        @Override
        public void run() {
            while (!cancelled) {
                synchronized (SUBSCRIBE_LOCK) {
                    if (pendingTopicsToSubscribe.isEmpty()) {
                        try {
                            LogUtils.d("wait subscribe");
                            SUBSCRIBE_LOCK.wait();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        LogUtils.d("wait subscribe over");
                        if (cancelled) {
                            LogUtils.w("subscribe task cancelled");
                            return;
                        }
                    }
                    if (!pendingTopicsToSubscribe.isEmpty()) {
                        if (currentSubscribingTopics.isEmpty()) {
                            currentSubscribingTopics.putAll(pendingTopicsToSubscribe);
                            pendingTopicsToSubscribe.clear();
                            String[] topics = currentSubscribingTopics.keySet().toArray(new String[0]);
                            Integer[] qos = currentSubscribingTopics.values().toArray(new Integer[0]);

                            doSubscribe(topics, Util.toIntArray(qos), new IActionCallBack() {
                                @Override
                                public void onSuccess() {
                                    if (isCancelled()) {
                                        return;
                                    }
                                    synchronized (SUBSCRIBE_LOCK) {
                                        subscribedTopics.putAll(currentSubscribingTopics);
                                        currentSubscribingTopics.clear();
                                    }
                                }

                                @Override
                                public void onError(String msg) {
                                    if (isCancelled()) {
                                        return;
                                    }
                                    synchronized (SUBSCRIBE_LOCK) {
                                        pendingTopicsToSubscribe.putAll(currentSubscribingTopics);
                                        currentSubscribingTopics.clear();
                                    }
                                }
                            });
                        }
                    }
                }
            }
        }

        void cancel() {
            cancelled = true;
            interrupt();
        }

        boolean isCancelled() {
            return cancelled;
        }
    }


}
