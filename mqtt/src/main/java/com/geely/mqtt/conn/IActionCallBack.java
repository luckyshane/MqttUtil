package com.geely.mqtt.conn;

/*
 * @author: luckyShane
 */
public interface IActionCallBack {
    void onSuccess();
    void onError(String msg);
}
