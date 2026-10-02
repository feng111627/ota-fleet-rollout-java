package com.github.feng111627.otafleetrolloutjava.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 波次拆分器：把有序设备条目按给定大小/波数切成若干有先后顺序的波次。
 * 纯函数，无状态，不依赖任何外部服务。拆分严格保持入参顺序，不重排、不去重。
 */
public final class WavePlanner {

    private WavePlanner() {
    }

    /**
     * 按每波设备数拆分：sizes 顺序即波次顺序，元素必须为正，且总和等于设备数。
     *
     * @throws IllegalArgumentException sizes 含非正数、为空，或总和与设备数不一致
     */
    public static List<Wave> planBySizes(List<BatchDeviceEntry> orderedEntries, List<Integer> sizes) {
        if (sizes == null || sizes.isEmpty()) {
            throw new IllegalArgumentException("wave sizes must not be empty");
        }
        int sum = 0;
        for (Integer size : sizes) {
            if (size == null || size <= 0) {
                throw new IllegalArgumentException("each wave size must be positive, got " + size);
            }
            sum += size;
        }
        if (sum != orderedEntries.size()) {
            throw new IllegalArgumentException(
                    "sum of wave sizes " + sum + " does not match device count " + orderedEntries.size());
        }
        List<Wave> waves = new ArrayList<>(sizes.size());
        int offset = 0;
        for (int waveNo = 0; waveNo < sizes.size(); waveNo++) {
            int size = sizes.get(waveNo);
            waves.add(new Wave(waveNo, new ArrayList<>(
                    orderedEntries.subList(offset, offset + size))));
            offset += size;
        }
        return waves;
    }

    /**
     * 按波数尽量均匀拆分（保持顺序）：设备数除以波数有余数时，
     * 多出的设备依次补到靠前的波次，保证不丢设备、不跳设备。
     *
     * @throws IllegalArgumentException waveCount 非正或大于设备数
     */
    public static List<Wave> planByWaveCount(List<BatchDeviceEntry> orderedEntries, int waveCount) {
        int total = orderedEntries.size();
        if (waveCount <= 0) {
            throw new IllegalArgumentException("waveCount must be positive, got " + waveCount);
        }
        if (waveCount > total) {
            throw new IllegalArgumentException(
                    "waveCount " + waveCount + " exceeds device count " + total);
        }
        int base = total / waveCount;
        int remainder = total % waveCount;
        List<Integer> sizes = new ArrayList<>(waveCount);
        for (int i = 0; i < waveCount; i++) {
            sizes.add(base + (i < remainder ? 1 : 0));
        }
        return planBySizes(orderedEntries, sizes);
    }
}
