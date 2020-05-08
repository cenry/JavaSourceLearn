package com.andycen.test.concurrent.countcompleter;

/**
 * @author cenruyi
 */
public class MyOperation<E> {
    void apply(E e) {
        System.out.println(String.format("[MyMapper] %s: %s", Thread.currentThread(), e));
    }
}
