package com.example.TestAPI.Repository;

import com.example.TestAPI.DTO.Admin.MonthlyRevenue;
import com.example.TestAPI.Model.Enum.PaymentStatus;
import com.example.TestAPI.Model.Transaction;
import com.example.TestAPI.Model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Optional<Transaction> findByJobId(UUID jobId);

    List<Transaction> findByBuyerOrderByCreatedAtDesc(User buyer);

    List<Transaction> findBySellerOrderByCreatedAtDesc(User seller);

    List<Transaction> findByStatus(PaymentStatus status);

    long countByStatus(PaymentStatus status);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.status = :status")
    BigDecimal sumAmountByStatus(@Param("status") PaymentStatus status);

    @Query("SELECT COALESCE(SUM(t.commissionAmount), 0) FROM Transaction t WHERE t.status = :status")
    BigDecimal sumCommissionByStatus(@Param("status") PaymentStatus status);

    @Query("SELECT COALESCE(SUM(t.netAmount), 0) FROM Transaction t WHERE t.status = :status")
    BigDecimal sumNetByStatus(@Param("status") PaymentStatus status);

    @Query("SELECT new com.example.TestAPI.DTO.Admin.MonthlyRevenue(" +
           "YEAR(t.createdAt), MONTH(t.createdAt), " +
           "COALESCE(SUM(t.amount), 0), COALESCE(SUM(t.commissionAmount), 0)) " +
           "FROM Transaction t WHERE t.status = 'COMPLETED' AND t.createdAt >= :since " +
           "GROUP BY YEAR(t.createdAt), MONTH(t.createdAt) " +
           "ORDER BY YEAR(t.createdAt) DESC, MONTH(t.createdAt) DESC")
    List<MonthlyRevenue> sumRevenueByMonth(@Param("since") Date since);
}
