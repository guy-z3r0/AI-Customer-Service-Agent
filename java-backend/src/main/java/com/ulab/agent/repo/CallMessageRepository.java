package com.ulab.agent.repo;

import com.ulab.agent.domain.CallMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CallMessageRepository extends JpaRepository<CallMessage, UUID> {

    List<CallMessage> findByCallIdOrderBySeqAsc(UUID callId);
}
