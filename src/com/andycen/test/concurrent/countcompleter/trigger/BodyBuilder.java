package com.andycen.test.concurrent.countcompleter.trigger;

import java.util.concurrent.CountedCompleter;

/**
 * @author cenruyi
 */
public class BodyBuilder<E> extends CountedCompleter<E> {

    public BodyBuilder(CountedCompleter<E> completer) {
        super(completer);
    }

    @Override
    public void compute() {
        System.out.println("BodyBuilder complete.");
        tryComplete();
    }
}
