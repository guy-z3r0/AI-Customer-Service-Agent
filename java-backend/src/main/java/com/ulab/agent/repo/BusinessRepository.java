package com.ulab.agent.repo;

import com.ulab.agent.domain.Business;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BusinessRepository extends JpaRepository<Business, UUID> {

    Optional<Business> findBySlug(String slug);

    boolean existsBySlug(String slug);

    Optional<Business> findFirstByActiveTrue();

    List<Business> findAllByOrderByNameAsc();

    /**
     * Clears the active flag everywhere else. Only one business may be active,
     * and the database enforces that with a unique index, so the old winner has
     * to be stood down in its own statement before the new one is promoted.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Business b set b.active = false where b.active = true and b.id <> :keepId")
    int deactivateAllExcept(@Param("keepId") UUID keepId);
}
