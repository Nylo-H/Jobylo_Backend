package com.example.TestAPI.Controller;

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
import com.example.TestAPI.Service.Admin.AdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminService adminService;

    @GetMapping("/stats")
    public ResponseEntity<AdminStatsResponse> getStats() {
        return ResponseEntity.ok(adminService.getStats());
    }

    @GetMapping("/finance")
    public ResponseEntity<FinanceStatsResponse> getFinanceStats() {
        return ResponseEntity.ok(adminService.getFinanceStats());
    }

    @GetMapping("/users")
    public ResponseEntity<List<UserResponse>> getAllUsers() {
        return ResponseEntity.ok(adminService.getAllUsers());
    }

    @GetMapping("/users/{userId}")
    public ResponseEntity<UserResponse> getUser(@PathVariable UUID userId) {
        return ResponseEntity.ok(adminService.getUserById(userId));
    }

    @PutMapping("/users/{userId}/role")
    public ResponseEntity<UserResponse> updateUserRole(
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateUserRoleRequest request) {
        return ResponseEntity.ok(adminService.updateUserRole(userId, request));
    }

    @PutMapping("/users/{userId}/kyc")
    public ResponseEntity<UserResponse> updateUserKyc(
            @AuthenticationPrincipal User admin,
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateKycRequest request) {
        return ResponseEntity.ok(adminService.updateUserKyc(admin, userId, request));
    }

    @DeleteMapping("/users/{userId}")
    public ResponseEntity<Map<String, String>> deleteUser(@PathVariable UUID userId) {
        adminService.deleteUser(userId);
        return ResponseEntity.ok(Map.of("message", "Utilisateur supprimé avec succès"));
    }

    @GetMapping("/jobs")
    public ResponseEntity<List<JobResponse>> getAllJobs() {
        return ResponseEntity.ok(adminService.getAllJobs());
    }

    @GetMapping("/transactions")
    public ResponseEntity<List<PaymentResponse>> getAllTransactions() {
        return ResponseEntity.ok(adminService.getAllTransactions());
    }

    @GetMapping("/audit")
    public ResponseEntity<List<ActionLogResponse>> getAllAuditLogs() {
        return ResponseEntity.ok(adminService.getAllAuditLogs());
    }

    @GetMapping("/categories")
    public ResponseEntity<List<CategoryResponse>> getAllCategories() {
        return ResponseEntity.ok(adminService.getAllCategories());
    }

    @PostMapping("/categories")
    public ResponseEntity<CategoryResponse> createCategory(
            @Valid @RequestBody CreateCategoryRequest request) {
        return ResponseEntity.ok(adminService.createCategory(request));
    }

    @PutMapping("/categories/{categoryId}")
    public ResponseEntity<CategoryResponse> updateCategory(
            @PathVariable UUID categoryId,
            @Valid @RequestBody CreateCategoryRequest request) {
        return ResponseEntity.ok(adminService.updateCategory(categoryId, request));
    }

    @DeleteMapping("/categories/{categoryId}")
    public ResponseEntity<Map<String, String>> deleteCategory(@PathVariable UUID categoryId) {
        adminService.deleteCategory(categoryId);
        return ResponseEntity.ok(Map.of("message", "Catégorie supprimée avec succès"));
    }
}
