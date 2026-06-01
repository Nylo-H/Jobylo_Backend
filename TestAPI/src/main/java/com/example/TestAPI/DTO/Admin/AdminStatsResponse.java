package com.example.TestAPI.DTO.Admin;

public record AdminStatsResponse(
        long totalUsers,
        long verifiedUsers,
        long kycPending,
        long kycVerified,
        long kycRejected,
        long jobsPending,
        long jobsInProgress,
        long jobsDone,
        long jobsExpired,
        long transactionsHeld,
        long transactionsCompleted,
        long transactionsCancelled,
        long totalApplications,
        long applicationsPending,
        long totalAuditLogs
) {}
