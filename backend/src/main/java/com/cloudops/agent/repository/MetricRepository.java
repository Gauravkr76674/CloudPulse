package com.cloudops.agent.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.cloudops.agent.entity.CloudResource;
import com.cloudops.agent.entity.Metric;

@Repository
public interface MetricRepository extends JpaRepository<Metric, Long> {

    /** Idempotency guard for the poller: has this exact datapoint already been stored? */
    boolean existsByResourceAndMetricNameAndStatisticAndTimestamp(
            CloudResource resource, String metricName, String statistic, Instant timestamp);

    Optional<Metric> findFirstByResourceAndMetricNameAndStatisticOrderByTimestampDesc(
            CloudResource resource, String metricName, String statistic);

    /** Most recent N datapoints (newest first) - used for the rolling baseline. */
    List<Metric> findByResourceAndMetricNameAndStatisticOrderByTimestampDesc(
            CloudResource resource, String metricName, String statistic, Pageable pageable);

    /** Chart series for one resource + metric. */
    List<Metric> findByResourceAndMetricNameAndTimestampAfterOrderByTimestampAsc(
            CloudResource resource, String metricName, Instant after);

    List<Metric> findByResourceAndTimestampAfterOrderByTimestampAsc(
            CloudResource resource, Instant after);

    /** Latest value of every metric of one resource. */
    @Query("""
            select m from Metric m
            where m.resource = :resource
              and m.timestamp = (
                    select max(m2.timestamp) from Metric m2
                    where m2.resource = :resource and m2.metricName = m.metricName
                        and m2.statistic = m.statistic)
            order by m.metricName asc
            """)
    List<Metric> findLatestPerMetric(@Param("resource") CloudResource resource);

    long countByAnomalousTrueAndTimestampAfter(Instant after);

    long countByTimestampAfter(Instant after);

    /** Retention cleanup (cloudops.monitoring.retention-days). */
    @Modifying
    @Query("delete from Metric m where m.timestamp < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);

    void deleteByResource(CloudResource resource);
}
