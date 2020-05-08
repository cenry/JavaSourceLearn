package com.andycen.test.concurrent.countcompleter;

import java.util.concurrent.CountedCompleter;

/**
 * @author cenruyi
 */
public class MapReducerV1<E> extends CountedCompleter<E> {

    public static void main(String[] args) {
        System.out.println(MapReducerV1.mapReduce(new Integer[]{1,2,3,4,5,6,7,8,9}, new MyMapper<>(), new MyReducer<>()));
    }

    final E[] array; final MyMapper<E> mapper;
    final MyReducer<E> reducer; final int lo, hi;
    MapReducerV1<E> sibling;
    E result;
    MapReducerV1(CountedCompleter<?> p, E[] array, MyMapper<E> mapper,
                 MyReducer<E> reducer, int lo, int hi) {
        super(p);
        this.array = array; this.mapper = mapper;
        this.reducer = reducer; this.lo = lo; this.hi = hi;
    }
    public void compute() {
        if (hi - lo >= 2) {
            int mid = (lo + hi) >>> 1;
            MapReducerV1<E> left = new MapReducerV1<>(this, array, mapper, reducer, lo, mid);
            MapReducerV1<E> right = new MapReducerV1<>(this, array, mapper, reducer, mid, hi);
            left.sibling = right;
            right.sibling = left;
            setPendingCount(1); // only right is pending
            right.fork();
            left.compute();     // directly execute left
        }
        else {
            if (hi > lo)
                result = mapper.apply(array[lo]);
            tryComplete();
        }
    }
    public void onCompletion(CountedCompleter<?> caller) {
        if (caller != this) {
            @SuppressWarnings("unchecked")
            MapReducerV1<E> child = (MapReducerV1<E>)caller;
            MapReducerV1<E> sib = child.sibling;
            if (sib == null || sib.result == null)
                result = child.result;
            else
                result = reducer.apply(child.result, sib.result);
        }
    }
    public E getRawResult() { return result; }

    public static <E> E mapReduce(E[] array, MyMapper<E> mapper, MyReducer<E> reducer) {
        return new MapReducerV1<>(null, array, mapper, reducer,
                0, array.length).invoke();
    }
}
