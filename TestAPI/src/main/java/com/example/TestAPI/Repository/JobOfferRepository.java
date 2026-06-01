package com.example.TestAPI.Repository;

import com.example.TestAPI.Model.Enum.JobStatus;
import com.example.TestAPI.Model.JobOffer;
import com.example.TestAPI.Model.User;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface JobOfferRepository extends JpaRepository<JobOffer, UUID>, JpaSpecificationExecutor<JobOffer> {
    List<JobOffer> findByCreatorId(UUID creatorId);
    List<JobOffer> findByCreatorOrderByCreatedAtDesc(User creator);
    List<JobOffer> findByWorkerOrderByCreatedAtDesc(User worker);
    List<JobOffer> findByStatus(JobStatus status);
    List<JobOffer> findByStatusOrderByCreatedAtDesc(JobStatus status);
    List<JobOffer> findByStatusAndLocationContainingIgnoreCase(JobStatus status, String location);

    List<JobOffer> findByWorkerId(UUID workerId);

    long countByCreatorAndStatus(User creator, JobStatus status);
    long countByWorkerAndStatus(User worker, JobStatus status);
    long countByCreator(User creator);
    long countByStatus(JobStatus status);
}
