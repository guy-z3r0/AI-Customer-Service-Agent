package com.ulab.agent.repo;

import com.ulab.agent.domain.AppConfigEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AppConfigRepository extends JpaRepository<AppConfigEntry, String> {

    List<AppConfigEntry> findAllByOrderByKeyAsc();
}
