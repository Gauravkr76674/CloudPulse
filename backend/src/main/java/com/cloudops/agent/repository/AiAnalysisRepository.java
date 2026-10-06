package com.cloudops.agent.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.cloudops.agent.entity.AiAnalysis;
import com.cloudops.agent.entity.AiStage;
import com.cloudops.agent.entity.Incident;

@Repository
public interface AiAnalysisRepository extends JpaRepository<AiAnalysis, Long> {

    List<AiAnalysis> findByIncidentOrderByCreatedAtAsc(Incident incident);

    Optional<AiAnalysis> findFirstByIncidentAndStageOrderByCreatedAtDesc(Incident incident, AiStage stage);

    List<AiAnalysis> findTop20ByOrderByCreatedAtDesc();

    long countBySuccessfulFalse();

    long count();
}
