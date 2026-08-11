package com.ulab.agent.repo;

import com.ulab.agent.domain.EscalationContact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface EscalationContactRepository extends JpaRepository<EscalationContact, UUID> {

    List<EscalationContact> findByBusinessIdOrderByPriorityAsc(UUID businessId);

    /** Same reason as the knowledge base's own bulk delete: ordering. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from EscalationContact c where c.businessId = :businessId")
    int deleteByBusinessId(@Param("businessId") UUID businessId);
}
