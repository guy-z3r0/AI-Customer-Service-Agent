package com.ulab.agent.repo;

import com.ulab.agent.domain.CallMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CallMessageRepository extends JpaRepository<CallMessage, UUID> {

    List<CallMessage> findByCallIdOrderBySeqAsc(UUID callId);

    Optional<CallMessage> findByCallIdAndSeq(UUID callId, int seq);

    /**
     * The highest sequence number this call has used, or 0 for a call with
     * nothing said on it yet.
     *
     * Asking the database for one number beats reading every line of the call
     * back to look at the last one, which is what this replaced — a transcript
     * grows all call and that read happened once per line.
     */
    @Query("SELECT COALESCE(MAX(m.seq), 0) FROM CallMessage m WHERE m.callId = :callId")
    int highestSeq(@Param("callId") UUID callId);
}
