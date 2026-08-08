package com.ulab.agent.repo;

import com.ulab.agent.domain.CallRecord;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CallRecordRepository extends JpaRepository<CallRecord, UUID> {

    List<CallRecord> findByBusinessIdOrderByStartedAtDesc(UUID businessId);

    /**
     * Takes the call's own row for the rest of the transaction, so that only one
     * writer at a time can be choosing the next line number for this call.
     *
     * Two turns landing together — a tool result written while a partial
     * arrives — used to read the same highest sequence number and both add one
     * to it. The unique constraint on (call_id, seq) turned that into a failed
     * insert and a lost line. This makes the second writer wait for the first,
     * which is a few microseconds on a call that produces a line every few
     * seconds.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT call FROM CallRecord call WHERE call.id = :callId")
    Optional<CallRecord> lockForNextSeq(@Param("callId") UUID callId);
}
