package com.github.feng111627.otafleetrolloutjava.rollout;

import com.github.feng111627.otafleetrolloutjava.model.Batch;
import com.github.feng111627.otafleetrolloutjava.model.DeviceUpgradeState;
import com.github.feng111627.otafleetrolloutjava.model.RollbackDeviceResult;
import com.github.feng111627.otafleetrolloutjava.model.WaveRollbackOutcome;
import com.github.feng111627.otafleetrolloutjava.model.WaveSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把扁平的逐设备回滚处置结果按设备所属波次汇总（纯函数，无副作用）。
 * 未放开过的波次也会出现在结果里（其设备为 ROLLBACK_SKIPPED，波次状态仍 PENDING），
 * 用于清晰展示“后面的波次原地未动”。
 */
public final class RollbackWaveGrouper {

    private RollbackWaveGrouper() {
    }

    public static List<WaveRollbackOutcome> group(Batch batch, List<RollbackDeviceResult> flat) {
        if (!batch.isWaveMode()) {
            return List.of();
        }
        Map<Integer, List<RollbackDeviceResult>> byWave = new LinkedHashMap<>();
        for (int i = 0; i < batch.waveCount(); i++) {
            byWave.put(i, new ArrayList<>());
        }
        for (RollbackDeviceResult result : flat) {
            int waveNo = batch.waveNumberOf(result.deviceId());
            byWave.computeIfAbsent(waveNo, k -> new ArrayList<>()).add(result);
        }

        List<WaveRollbackOutcome> outcomes = new ArrayList<>(byWave.size());
        for (WaveSnapshot waveSnapshot : batch.waveSnapshots()) {
            int waveNo = waveSnapshot.waveNo();
            List<RollbackDeviceResult> results = byWave.getOrDefault(waveNo, List.of());
            int rolledBack = 0;
            int skipped = 0;
            int failed = 0;
            for (RollbackDeviceResult result : results) {
                if (result.outcome() == DeviceUpgradeState.ROLLBACK_SUCCEEDED) {
                    rolledBack++;
                } else if (result.outcome() == DeviceUpgradeState.ROLLBACK_SKIPPED) {
                    skipped++;
                } else {
                    failed++;
                }
            }
            outcomes.add(new WaveRollbackOutcome(waveNo, waveSnapshot.status(),
                    List.copyOf(results), rolledBack, skipped, failed));
        }
        return outcomes;
    }
}
