package com.github.feng111627.otafleetrolloutjava.rollout;

import com.github.feng111627.otafleetrolloutjava.model.Batch;
import com.github.feng111627.otafleetrolloutjava.model.DeviceUpgradeState;
import com.github.feng111627.otafleetrolloutjava.model.RollbackDeviceResult;
import com.github.feng111627.otafleetrolloutjava.model.RollbackOutcome;
import com.github.feng111627.otafleetrolloutjava.model.WaveRollbackSummary;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 回滚结果聚合：在逐设备处置结果之上汇总整批计数，并按波次分别给出处置统计。
 * 纯计算、无副作用，回滚执行与幂等复现共用，保证两条路径得到结构一致的结果。
 */
final class RollbackOutcomeFactory {

    private RollbackOutcomeFactory() {
    }

    static RollbackOutcome build(Batch batch, List<RollbackDeviceResult> results) {
        int rolledBackSucceeded = 0;
        int skippedNotStarted = 0;
        int failedDevices = 0;

        // 以波次下标分桶（TreeMap 保证按波次顺序输出），缺失波次补 0
        Map<Integer, int[]> byWave = new TreeMap<>();
        for (int w = 0; w < batch.getWaveCount(); w++) {
            byWave.put(w, new int[4]); // 0:总数 1:回滚恢复 2:跳过 3:人工/失败
        }

        for (RollbackDeviceResult r : results) {
            int[] bucket = byWave.computeIfAbsent(r.waveIndex(), k -> new int[4]);
            switch (r.outcome()) {
                case ROLLBACK_SUCCEEDED -> {
                    rolledBackSucceeded++;
                    bucket[1]++;
                }
                case ROLLBACK_SKIPPED -> {
                    skippedNotStarted++;
                    bucket[2]++;
                }
                default -> {
                    failedDevices++;
                    bucket[3]++;
                }
            }
        }

        // 每波总数以波次定义为准，避免“处置结果里漏了某波”时统计漂移
        for (var wave : batch.snapshotWaves()) {
            byWave.computeIfAbsent(wave.getIndex(), k -> new int[4]);
            byWave.get(wave.getIndex())[0] = wave.getDeviceIds().size();
        }

        List<WaveRollbackSummary> summaries = new ArrayList<>(byWave.size());
        for (Map.Entry<Integer, int[]> e : byWave.entrySet()) {
            int[] c = e.getValue();
            summaries.add(new WaveRollbackSummary(e.getKey(), c[0], c[1], c[2], c[3]));
        }

        return new RollbackOutcome(batch.getBatchId(), batch.getStatus(),
                rolledBackSucceeded, skippedNotStarted, failedDevices, results, summaries);
    }
}
