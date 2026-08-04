package com.ulab.agent.repo;

import com.ulab.agent.domain.KbEntry;
import com.ulab.agent.domain.enums.KbKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface KbEntryRepository extends JpaRepository<KbEntry, UUID> {

    List<KbEntry> findByBusinessIdOrderByKindAscSortOrderAsc(UUID businessId);

    List<KbEntry> findByBusinessIdAndKindOrderBySortOrderAsc(UUID businessId, KbKind kind);

    long countByBusinessId(UUID businessId);
}
