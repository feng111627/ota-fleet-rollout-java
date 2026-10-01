package com.github.feng111627.otafleetrolloutjava.repository;

import com.github.feng111627.otafleetrolloutjava.model.RolloutBatch;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 发布批次本地存储（内存）。 */
@Repository
public class RolloutRepository {
    private final Map<String, RolloutBatch> batches = new ConcurrentHashMap<>();

    public RolloutBatch save(RolloutBatch batch) {
        batches.put(batch.getId(), batch);
        return batch;
    }

    public Optional<RolloutBatch> findById(String id) {
        return Optional.ofNullable(batches.get(id));
    }

    public List<RolloutBatch> findAll() {
        return new ArrayList<>(batches.values());
    }
}
