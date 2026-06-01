package com.example.TestAPI.Service.Admin;

import com.example.TestAPI.DTO.Admin.AdminStatsResponse;
import com.example.TestAPI.DTO.Admin.CreateCategoryRequest;
import com.example.TestAPI.DTO.Admin.FinanceStatsResponse;
import com.example.TestAPI.DTO.Admin.UpdateKycRequest;
import com.example.TestAPI.DTO.Admin.UpdateUserRoleRequest;
import com.example.TestAPI.DTO.Audit.ActionLogResponse;
import com.example.TestAPI.DTO.Category.CategoryResponse;
import com.example.TestAPI.DTO.Job.JobResponse;
import com.example.TestAPI.DTO.Payment.PaymentResponse;
import com.example.TestAPI.DTO.User.UserResponse;
import com.example.TestAPI.Model.User;

import java.util.List;
import java.util.UUID;

public interface AdminService {

    AdminStatsResponse getStats();

    FinanceStatsResponse getFinanceStats();

    List<UserResponse> getAllUsers();

    UserResponse getUserById(UUID userId);

    UserResponse updateUserRole(UUID userId, UpdateUserRoleRequest request);

    UserResponse updateUserKyc(User admin, UUID userId, UpdateKycRequest request);

    void deleteUser(UUID userId);

    List<JobResponse> getAllJobs();

    List<PaymentResponse> getAllTransactions();

    List<ActionLogResponse> getAllAuditLogs();

    List<CategoryResponse> getAllCategories();

    CategoryResponse createCategory(CreateCategoryRequest request);

    CategoryResponse updateCategory(UUID categoryId, CreateCategoryRequest request);

    void deleteCategory(UUID categoryId);
}
