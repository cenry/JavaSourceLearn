package com.andycen.test.concurrent.countcompleter;

import java.util.concurrent.CountedCompleter;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author cenruyi
 */
public class Searcher<E> extends CountedCompleter<E> {

    public static void main(String[] args) {
        System.out.println(Searcher.search(new Integer[]{1,3,5,7,9}));
    }

    final E[] array; final AtomicReference<E> result; final int lo, hi;
    Searcher(CountedCompleter<?> p, E[] array, AtomicReference<E> result, int lo, int hi) {
        super(p);
        this.array = array; this.result = result; this.lo = lo; this.hi = hi;
    }
    public E getRawResult() { return result.get(); }
    public void compute() { // similar to ForEach version 3
        int l = lo,  h = hi;
        while (result.get() == null && h >= l) {
            if (h - l >= 2) {
                int mid = (l + h) >>> 1;
                addToPendingCount(1);
                new Searcher<>(this, array, result, mid, h).fork();
                h = mid;
            }
            else {
                E x = array[l];
                if (matches(x) && result.compareAndSet(null, x))
                    quietlyCompleteRoot(); // root task is now joinable
                break;
            }
        }
        tryComplete(); // normally complete whether or not found
    }
    boolean matches(E e) {
        if (e instanceof Integer) {
            return Integer.parseInt(e.toString()) > 5;
        }
        return false;
    } // return true if found

    public static <E> E search(E[] array) {
        return new Searcher<>(null, array, new AtomicReference<>(), 0, array.length).invoke();
    }
}
