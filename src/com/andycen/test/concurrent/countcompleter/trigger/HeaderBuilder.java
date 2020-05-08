package com.andycen.test.concurrent.countcompleter.trigger;

import java.util.concurrent.CountedCompleter;

/**
 * @author cenruyi
 */
public class HeaderBuilder<E> extends CountedCompleter<E> {

    public HeaderBuilder(CountedCompleter<E> completer) {
        super(completer);
    }

    @Override
    public void compute() {
        System.out.println("HeaderBuilder complete.");
        tryComplete();
    }
}
