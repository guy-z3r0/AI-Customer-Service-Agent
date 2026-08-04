package com.ulab.agent.repo;

import com.ulab.agent.domain.Client;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClientRepository extends JpaRepository<Client, UUID> {

    List<Client> findByBusinessIdOrderByClientCodeAsc(UUID businessId);

    Optional<Client> findByBusinessIdAndClientCode(UUID businessId, String clientCode);

    long countByBusinessId(UUID businessId);
}
