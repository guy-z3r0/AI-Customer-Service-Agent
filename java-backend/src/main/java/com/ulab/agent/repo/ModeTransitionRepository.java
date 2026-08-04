package com.ulab.agent.repo;

import com.ulab.agent.domain.ModeTransition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ModeTransitionRepository extends JpaRepository<ModeTransition, UUID> {

    List<ModeTransition> findByCallIdOrderByAtAsc(UUID callId);
}
