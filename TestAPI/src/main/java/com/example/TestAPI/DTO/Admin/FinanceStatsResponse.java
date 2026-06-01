package com.example.TestAPI.DTO.Admin;

import java.math.BigDecimal;
import java.util.List;

public record FinanceStatsResponse(
        BigDecimal totalVolume,
        BigDecimal heldAmount,
        BigDecimal totalCommissionCollected,
        BigDecimal totalPaidToWorkers,
        long totalTransactions,
        long completedTransactions,
        long heldTransactions,
        long cancelledTransactions,
        BigDecimal averageTransactionAmount,
        List<MonthlyRevenue> revenueByMonth
) {}
