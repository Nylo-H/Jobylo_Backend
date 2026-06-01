package com.example.TestAPI.DTO.Job;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.UUID;

public record JobResponse(
        UUID id,
        String title,
        String description,
        String location,
        BigDecimal price,
        UUID creatorId,
        String creatorUsername,
        UUID workerId,
        String workerUsername,
        String status,
        Date createdAt,
        Date updatedAt,
        List<String> images,
        UUID categoryId,
        String categoryName,
        Date applicationDeadline
) { }
