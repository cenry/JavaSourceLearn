package com.andycen.test.map;

import java.util.HashMap;

/**
 * @author cenruyi
 */
public class HashMapTest {

    public static void main(String[] args) {
        hashCalculateDeadLoop();
    }

    /**
     * this method will lead to a dead loop
     */
    public static void hashCalculateDeadLoop() {
        HashMap<String, HashMap> map = new HashMap<>();
        map.put("map",map);
        System.out.println(map.hashCode());
    }
}
