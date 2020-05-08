package com.andycen.test.concurrent.countcompleter;

import java.util.concurrent.CountedCompleter;

/**
 * @author cenruyi
 */
public class ForEachV3<E> extends CountedCompleter<Void> {
    public static void main(String[] args) {
        ForEachV3.forEach(new Integer[]{1,2,3,4,5,6,7,8,9}, new MyOperation<>());
    }

    public static <E> void forEach(E[] array, MyOperation<E> op) {
        new ForEachV3<>(null, array, op, 0, array.length).invoke();
    }

    final E[] array; final MyOperation<E> op; final int lo, hi;
    ForEachV3(CountedCompleter<?> p, E[] array, MyOperation<E> op, int lo, int hi) {
        super(p);
        this.array = array; this.op = op; this.lo = lo; this.hi = hi;
    }

    public void compute() { // version 3
        int l = lo,  h = hi;
        while (h - l >= 2) {
            int mid = (l + h) >>> 1;
            addToPendingCount(1);
            new ForEachV3<>(this, array, op, mid, h).fork(); // right child
            h = mid;
        }
        if (h > l)
            op.apply(array[l]);
        propagateCompletion();
    }
}
