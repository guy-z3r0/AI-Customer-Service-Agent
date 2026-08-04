package com.ulab.agent.repo;

import com.ulab.agent.domain.CallRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CallRecordRepository extends JpaRepository<CallRecord, UUID> {

    List<CallRecord> findByBusinessIdOrderByStartedAtDesc(UUID businessId);
}
