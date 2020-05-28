package com.andycen.test.concurrent.forkjoin;

import java.util.concurrent.CountedCompleter;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.RecursiveTask;

/**
 * @author cenruyi
 */
public class ForkJoinPoolTest2 {

    public static void main(String[] args) {
        ForkJoinPool forkJoinPool = ForkJoinPool.commonPool();

        MyCountedCompleter myCountedCompleter = new MyCountedCompleter();
        forkJoinPool.invoke(myCountedCompleter);
        myCountedCompleter.join();

//        MyRecursiveAction myRecursiveAction = new MyRecursiveAction();
//        myRecursiveAction.join();

//        MyRecursiveTask myRecursiveTask = new MyRecursiveTask();
//        Integer invoke = forkJoinPool.invoke(myRecursiveTask);
//        System.out.println(invoke);

        System.out.println(forkJoinPool.toString());
    }

    public static class MyCountedCompleter extends CountedCompleter<Integer> {

        @Override
        public void compute() {
            System.out.println(Thread.currentThread().getName());
            tryComplete();
        }
    }

    public static class MyRecursiveAction extends RecursiveAction {

        @Override
        protected void compute() {
            System.out.println(Thread.currentThread().getName());
        }
    }

    public static class MyRecursiveTask extends RecursiveTask<Integer> {

        @Override
        protected Integer compute() {
            System.out.println(Thread.currentThread().getName());
            return 1;
        }
    }

}
