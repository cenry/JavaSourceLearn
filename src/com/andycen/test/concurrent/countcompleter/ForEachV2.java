package com.andycen.test.concurrent.countcompleter;

import java.util.concurrent.CountedCompleter;

/**
 * @author cenruyi
 */
public class ForEachV2<E> extends CountedCompleter<Void> {

    public static void main(String[] args) {
        ForEachV2.forEach(new Integer[]{1,2,3,4,5,6,7,8,9}, new MyOperation<>());
    }

    public static <E> void forEach(E[] array, MyOperation<E> op) {
        new ForEachV2<>(null, array, op, 0, array.length).invoke();
    }

    final E[] array; final MyOperation<E> op; final int lo, hi;
    ForEachV2(CountedCompleter<?> p, E[] array, MyOperation<E> op, int lo, int hi) {
        super(p);
        this.array = array; this.op = op; this.lo = lo; this.hi = hi;
    }

    public void compute() { // version 2
        if (hi - lo >= 2) {
            int mid = (lo + hi) >>> 1;
            setPendingCount(1); // only one pending
            new ForEachV2<>(this, array, op, mid, hi).fork(); // right child
            new ForEachV2<>(this, array, op, lo, mid).compute(); // direct invoke
        } else {
            if (hi > lo)
                op.apply(array[lo]);
            tryComplete();
        }
    }
}
