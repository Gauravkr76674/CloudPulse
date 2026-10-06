package com.cloudops.agent.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.cloudops.agent.entity.ActionExecution;
import com.cloudops.agent.entity.ActionStatus;
import com.cloudops.agent.entity.CloudResource;
import com.cloudops.agent.entity.Incident;

@Repository
public interface ActionExecutionRepository extends JpaRepository<ActionExecution, Long> {

    List<ActionExecution> findByIncidentOrderByCreatedAtDesc(Incident incident);

    Page<ActionExecution> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<ActionExecution> findByStatusOrderByCreatedAtDesc(ActionStatus status, Pageable pageable);

    Page<ActionExecution> findByIncidentOrderByCreatedAtDesc(Incident incident, Pageable pageable);

    /** Rate-limit input: how many executions this resource had in the recent window. */
    long countByResourceAndCreatedAtAfterAndStatusIn(
            CloudResource resource, Instant after, Collection<ActionStatus> statuses);

    long countByStatus(ActionStatus status);

    List<ActionExecution> findTop10ByOrderByCreatedAtDesc();
}
