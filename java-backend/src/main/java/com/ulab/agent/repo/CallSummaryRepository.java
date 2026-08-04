package com.ulab.agent.repo;

import com.ulab.agent.domain.CallSummary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CallSummaryRepository extends JpaRepository<CallSummary, UUID> {
}
