package com.example.TestAPI.Service.JobOffer;

import com.example.TestAPI.DTO.Job.AssignJobRequest;
import com.example.TestAPI.DTO.Job.CreateJobRequest;
import com.example.TestAPI.DTO.Job.UpdateJobRequest;
import com.example.TestAPI.Model.Enum.JobStatus;
import com.example.TestAPI.Model.JobOffer;
import com.example.TestAPI.Model.User;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface JobService {
    JobOffer createJob(User creator, CreateJobRequest request);
    JobOffer updateJob(UUID jobId, User currentUser, UpdateJobRequest request);
    JobOffer assignJob(UUID jobId, User currentUser, AssignJobRequest request);
    JobOffer updateJobStatus(UUID jobId, User currentUser, JobStatus status);
    JobOffer getJobById(UUID jobId);
    List<JobOffer> getJobsCreatedByUser(User user);
    List<JobOffer> getJobsAssignedToUser(User user);
    List<JobOffer> getAvailableJobs(String categoryId, String q, BigDecimal minPrice, BigDecimal maxPrice, String sort, String location);
    void deleteJob(UUID jobId, User currentUser);
    JobOffer addImages(UUID jobId, User currentUser, List<String> imageUrls);
    JobOffer removeImage(UUID jobId, User currentUser, String imageUrl);
    JobOffer expireJob(UUID jobId, User currentUser);
}
