package com.geely.mqtt;

/*
 * @author: luckyShane
 */
public class Util {

    public static int[] toIntArray(Integer[] array) {
        int[] ret = new int[array.length];
        int i = 0;
        for (Integer e : array)
            ret[i++] = e;
        return ret;
    }

    public static void validateQos(int qos) {
        if (qos < 0 || qos > 2) {
            throw new IllegalArgumentException("qos is invalid");
        }
    }


}
