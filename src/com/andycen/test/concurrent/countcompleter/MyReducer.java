package com.andycen.test.concurrent.countcompleter;

/**
 * @author cenruyi
 */
public class MyReducer<E> {

    E apply(E x, E y) {
        System.out.println(String.format("[MyReducer] %s: %s & %s", Thread.currentThread(), x, y));
        if (x instanceof Integer && y instanceof Integer) {
            return Integer.parseInt(x.toString()) > Integer.parseInt(y.toString()) ? x : y;
        }
        return null;
    }
}
