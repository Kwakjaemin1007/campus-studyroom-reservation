package com.studyroom.device.modifiable;

import com.studyroom.control.modifiable.api.ExamPeriod;
import com.studyroom.control.modifiable.api.IExamPeriodStore;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryExamPeriodStore implements IExamPeriodStore {
    private final Map<String, ExamPeriod> periods = new ConcurrentHashMap<>();

    @Override
    public List<ExamPeriod> all() {
        return periods.values().stream().sorted(Comparator.comparing(ExamPeriod::start)).toList();
    }

    @Override
    public void save(ExamPeriod period) {
        periods.put(period.id(), period);
    }

    @Override
    public boolean delete(String id) {
        return periods.remove(id) != null;
    }
}
