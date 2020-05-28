package com.andycen.test.concurrent.forkjoin;

import java.util.concurrent.CountedCompleter;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

/**
 * @author cenruyi
 */
public class ForkJoinPoolTest3 {

    public static void main(String[] args) throws InterruptedException {
//        ForkJoinPool forkJoinPool = new ForkJoinPool();
        for (int i = 0; i < 3; i++) {
            MyCountedCompleter myCountedCompleter = new MyCountedCompleter();
//            forkJoinPool.submit(myCountedCompleter);
            myCountedCompleter.fork();
        }
        System.out.println(ForkJoinPool.commonPool().getActiveThreadCount());
        Thread.sleep(200000000);
//        forkJoinPool.shutdown();
//        System.out.println(forkJoinPool.isShutdown());
//        System.out.println("quiescence: " + forkJoinPool.awaitQuiescence(5000, TimeUnit.MILLISECONDS));
//        System.out.println("termination: " + forkJoinPool.awaitTermination(1000000, TimeUnit.MILLISECONDS));
//        System.out.println(forkJoinPool.toString());
    }

    public static class MyCountedCompleter extends CountedCompleter<Integer> {

        @Override
        public void compute() {
            try {
                Thread.sleep(1000);
                System.out.println(Thread.currentThread().getName());
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            tryComplete();
        }
    }
}
