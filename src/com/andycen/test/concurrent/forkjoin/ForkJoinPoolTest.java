package com.andycen.test.concurrent.forkjoin;

import java.util.concurrent.ForkJoinPool;

/**
 * @author cenruyi
 */
public class ForkJoinPoolTest {
    public static void main(String[] args) {
        // AC_MASK ffff 0000 0000 0000
        // TC_MASK 0000 ffff 0000 0000
        long ac_mask = 0xffff000000000000L;
        long tc_mask = 0x0000ffff00000000L;
//        int maxCap = 0x7fff; // 0000 7fff
        int maxCap = 11;
        long np = (long)(-maxCap);// 0xffff8001
        System.out.println(Long.toHexString((np << 48) & ac_mask));
        System.out.println(Long.toHexString((np << 32) & tc_mask));
        System.out.println(ForkJoinPool.commonPool().toString());
    }
}
