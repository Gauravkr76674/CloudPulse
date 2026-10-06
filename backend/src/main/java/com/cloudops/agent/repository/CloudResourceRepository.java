package com.cloudops.agent.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.cloudops.agent.entity.CloudResource;
import com.cloudops.agent.entity.ResourceStatus;
import com.cloudops.agent.entity.ResourceType;

@Repository
public interface CloudResourceRepository extends JpaRepository<CloudResource, Long> {

    Optional<CloudResource> findByResourceId(String resourceId);

    boolean existsByResourceId(String resourceId);

    List<CloudResource> findByMonitoredTrue();

    List<CloudResource> findByType(ResourceType type);

    List<CloudResource> findAllByOrderByNameAsc();

    long countByMonitoredTrue();

    long countByStatus(ResourceStatus status);

    /**
     * Free-text filter used by GET /api/resources?q=...
     * A null/blank term returns everything (the COALESCE keeps the query null-safe).
     */
    @Query("""
            select r from CloudResource r
            where (:term is null or lower(r.name) like lower(concat('%', cast(:term as string), '%'))
                   or lower(r.resourceId) like lower(concat('%', cast(:term as string), '%')))
            order by r.name asc
            """)
    List<CloudResource> search(@Param("term") String term);
}
