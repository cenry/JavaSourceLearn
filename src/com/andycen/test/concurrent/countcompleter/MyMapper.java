package com.andycen.test.concurrent.countcompleter;

/**
 * @author cenruyi
 */
public class MyMapper<E> {

    E apply(E v) {
        System.out.println(String.format("[MyMapper] %s: %s", Thread.currentThread(), v));
        return v;
    }
}
