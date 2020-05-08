package com.andycen.test.concurrent.countcompleter;

import java.util.concurrent.CountedCompleter;

/**
 * @author cenruyi
 */
public class MapReducerV2<E> extends CountedCompleter<E> {

    public static void main(String[] args) {
        System.out.println(MapReducerV2.mapReduce(new Integer[]{0,1,2,3,4,5,6,7,8}, new MyMapper<>(), new MyReducer<>()));
    }

    final E[] array; final MyMapper<E> mapper;
    final MyReducer<E> reducer; final int lo, hi;
    MapReducerV2<E> forks, next; // record subtask forks in list
    E result;
    MapReducerV2(CountedCompleter<?> p, E[] array, MyMapper<E> mapper,
               MyReducer<E> reducer, int lo, int hi, MapReducerV2<E> next) {
        super(p);
        this.array = array; this.mapper = mapper;
        this.reducer = reducer; this.lo = lo; this.hi = hi;
        this.next = next;
    }
    public void compute() {
        int l = lo,  h = hi;
        while (h - l >= 2) {
            int mid = (l + h) >>> 1;
            addToPendingCount(1);
            (forks = new MapReducerV2<>(this, array, mapper, reducer, mid, h, forks)).fork();
            h = mid;
        }
        if (h > l)
            result = mapper.apply(array[l]);
        // process completions by reducing along and advancing subtask links
        for (CountedCompleter<?> c = firstComplete(); c != null; c = c.nextComplete()) {
            for (MapReducerV2 t = (MapReducerV2)c, s = t.forks;  s != null; s = t.forks = s.next)
                t.result = reducer.apply((E)t.result, (E)s.result);
        }
    }
    public E getRawResult() { return result; }

    public static <E> E mapReduce(E[] array, MyMapper<E> mapper, MyReducer<E> reducer) {
        return new MapReducerV2<E>(null, array, mapper, reducer,
                0, array.length, null).invoke();
    }
}
