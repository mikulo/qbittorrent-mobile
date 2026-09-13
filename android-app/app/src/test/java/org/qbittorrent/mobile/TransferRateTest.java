package org.qbittorrent.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class TransferRateTest {
    @Test public void halfSecondSamplesUseActualByteDeltaWithoutSmoothing() {
        TransferRate rate = new TransferRate();
        assertEquals(0, rate.sample(100, 1000));
        assertEquals(2048, rate.sample(1124, 1500));
        assertEquals(4096, rate.sample(3172, 2000));
        assertEquals(0, rate.sample(3172, 2500));
    }

    @Test public void delayedCallbacksUseNativeSampleInterval() {
        TransferRate rate = new TransferRate();
        rate.sample(0, 1000);
        assertEquals(1000, rate.sample(10000, 11000));
        assertEquals(1000, rate.sample(99999, 11000)); // duplicate timestamp is ignored
        assertEquals(2000, rate.sample(11000, 11500));
    }

    @Test public void pausedCountersAndRecreatedSessionRebaselineWithoutSpike() {
        TransferRate rate = new TransferRate();
        rate.sample(900000, 1000);
        assertEquals(0, rate.sample(0, 1500));
        assertEquals(2000, rate.sample(1000, 2000));
        assertEquals(0, rate.sample(4000, 100));
        assertEquals(2000, rate.sample(5000, 600));
    }

    @Test public void nativeRequestBacklogRemainsOneDuringTenSecondStall() {
        StateRequestGate gate = new StateRequestGate();
        assertTrue(gate.request(1000));
        for (int i = 0; i < 10000; i++) assertFalse(gate.request(1000 + i));
        assertEquals(10000, gate.pendingAge(11000));
        assertEquals(10000, gate.complete(11000));
        assertEquals(0, gate.pendingAge(11000));
        assertTrue(gate.request(11000));
        gate.complete(11001);
        assertFalse(gate.request(11999));
        assertTrue(gate.request(12000));
    }
}
