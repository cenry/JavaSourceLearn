package com.andycen.test.concurrent.countcompleter.trigger;

import java.util.concurrent.CountedCompleter;

/**
 * @author cenruyi
 */
public class PacketSender extends CountedCompleter<String> {
    PacketSender() { super(null, 1); } // trigger on second completion
    public void compute() { } // never called
    public void onCompletion(CountedCompleter<?> caller) { sendPacket(); }
    private void sendPacket() {
        System.out.println("trigger!");
    }

    public static void main(String[] args) {
        PacketSender p = new PacketSender();
        new HeaderBuilder<>(p).compute();
        new BodyBuilder<>(p).compute();
    }
}
