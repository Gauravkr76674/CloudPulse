package com.cloudops.agent.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.cloudops.agent.entity.Incident;
import com.cloudops.agent.entity.Recommendation;
import com.cloudops.agent.entity.RecommendationStatus;

@Repository
public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {

    List<Recommendation> findByIncidentOrderByPriorityAscCreatedAtAsc(Incident incident);

    List<Recommendation> findByStatusOrderByPriorityAscCreatedAtDesc(RecommendationStatus status);

    List<Recommendation> findByStatusInOrderByCreatedAtDesc(Collection<RecommendationStatus> statuses);

    List<Recommendation> findByIncidentAndStatus(Incident incident, RecommendationStatus status);

    long countByStatus(RecommendationStatus status);

    /**
     * The owning incident's id without loading the incident.
     *
     * {@code Recommendation.incident} is LAZY, so the executor — which runs outside a
     * transaction while it talks to AWS — cannot navigate the association. It asks for the
     * id here and then loads the incident through {@code IncidentService}.
     */
    @Query("select r.incident.id from Recommendation r where r.id = :id")
    Optional<Long> findIncidentIdById(@Param("id") Long id);
}
