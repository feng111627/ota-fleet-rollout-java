package com.github.feng111627.otafleetrolloutjava.tests;

import com.github.feng111627.otafleetrolloutjava.model.BatchDeviceEntry;
import com.github.feng111627.otafleetrolloutjava.model.Wave;
import com.github.feng111627.otafleetrolloutjava.model.WavePlanner;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 波次拆分规则：保持建批顺序、按大小/按波数拆分、余数补前波、非法入参拒绝。
 */
class WavePlannerTest {

    private static final Logger log = LoggerFactory.getLogger(WavePlannerTest.class);

    private List<BatchDeviceEntry> entries(int n) {
        List<BatchDeviceEntry> list = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            list.add(new BatchDeviceEntry("d" + i, "1.0.0"));
        }
        return list;
    }

    @Test
    void planBySizesKeepsOrderAndBoundaries() {
        List<Wave> waves = WavePlanner.planBySizes(entries(6), List.of(1, 2, 3));
        assertEquals(3, waves.size());
        assertEquals(List.of("d1"), ids(waves.get(0)));
        assertEquals(List.of("d2", "d3"), ids(waves.get(1)));
        assertEquals(List.of("d4", "d5", "d6"), ids(waves.get(2)));
        log.info("[plan] sizes 1,2,3 over 6 devices keeps order and no overlap/gap");

        assertThrows(IllegalArgumentException.class,
                () -> WavePlanner.planBySizes(entries(6), List.of(1, 2, 2)), "总和不符应拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> WavePlanner.planBySizes(entries(6), List.of(2, 0, 4)), "0 大小波应拒绝");
    }

    @Test
    void planByWaveCountDistributesRemainderToFrontWaves() {
        // 7 台分 3 波 => 3,2,2（余数补前波）
        List<Wave> waves = WavePlanner.planByWaveCount(entries(7), 3);
        assertEquals(List.of(3, 2, 2), waves.stream().map(Wave::size).toList());
        assertEquals(List.of("d1", "d2", "d3"), ids(waves.get(0)));
        assertEquals(List.of("d6", "d7"), ids(waves.get(2)));
        log.info("[plan] 7 devices / 3 waves => 3,2,2 (remainder fills front waves)");

        // 整除时严格均匀
        assertEquals(List.of(2, 2, 2),
                WavePlanner.planByWaveCount(entries(6), 3).stream().map(Wave::size).toList());

        assertThrows(IllegalArgumentException.class,
                () -> WavePlanner.planByWaveCount(entries(2), 3), "波数大于设备数应拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> WavePlanner.planByWaveCount(entries(2), 0), "非正波数应拒绝");
    }

    private List<String> ids(Wave wave) {
        return wave.getEntries().stream().map(BatchDeviceEntry::getDeviceId).toList();
    }
}
