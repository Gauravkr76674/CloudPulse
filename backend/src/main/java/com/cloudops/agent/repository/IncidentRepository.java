package com.cloudops.agent.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.cloudops.agent.entity.CloudResource;
import com.cloudops.agent.entity.Incident;
import com.cloudops.agent.entity.IncidentStatus;
import com.cloudops.agent.entity.Severity;

/**
 * JpaSpecificationExecutor is used instead of a @Query with many "is null" branches:
 * the incident list screen filters by any combination of status, severity and resource.
 */
@Repository
public interface IncidentRepository extends JpaRepository<Incident, Long>, JpaSpecificationExecutor<Incident> {

    /** Active-incident lookup for deduplication (activeFlag is 1 while active). */
    Optional<Incident> findByDedupKeyAndActiveFlag(String dedupKey, Long activeFlag);

    List<Incident> findByStatusIn(Collection<IncidentStatus> statuses);

    List<Incident> findByResourceAndStatusIn(CloudResource resource, Collection<IncidentStatus> statuses);

    List<Incident> findTop10ByOrderByOpenedAtDesc();

    List<Incident> findByStatusInOrderByOpenedAtDesc(Collection<IncidentStatus> statuses, Pageable pageable);

    long countByStatus(IncidentStatus status);

    /** Active-incident badge on the resource list. */
    long countByResourceAndStatusIn(CloudResource resource, Collection<IncidentStatus> statuses);

    long countByStatusIn(Collection<IncidentStatus> statuses);

    long countBySeverityAndStatusIn(Severity severity, Collection<IncidentStatus> statuses);

    long countByOpenedAtAfter(Instant after);

    long countByResolvedAtAfter(Instant after);

    void deleteByResource(CloudResource resource);

    /** Mean time to resolve, in seconds, over resolved incidents since a cutoff. */
    @Query("""
            select avg(function('timestampdiff', second, i.openedAt, i.resolvedAt))
            from Incident i
            where i.resolvedAt is not null and i.resolvedAt > :after
            """)
    Double averageResolutionSeconds(@Param("after") Instant after);
}
