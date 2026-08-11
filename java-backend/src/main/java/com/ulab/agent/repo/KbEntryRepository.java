package com.ulab.agent.repo;

import com.ulab.agent.domain.KbEntry;
import com.ulab.agent.domain.enums.KbKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface KbEntryRepository extends JpaRepository<KbEntry, UUID> {

    List<KbEntry> findByBusinessIdOrderByKindAscSortOrderAsc(UUID businessId);

    List<KbEntry> findByBusinessIdAndKindOrderBySortOrderAsc(UUID businessId, KbKind kind);

    long countByBusinessId(UUID businessId);

    /**
     * Empties a business's knowledge base, for an import that replaces it.
     *
     * One statement rather than a row at a time, because the rows the caller is
     * about to write are queued in the same transaction — and Hibernate flushes
     * its inserts before its deletes, which would put the new entries in ahead
     * of the old ones going out.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from KbEntry k where k.businessId = :businessId")
    int deleteByBusinessId(@Param("businessId") UUID businessId);
}
