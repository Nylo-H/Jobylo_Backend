package com.example.TestAPI.DTO.Admin;

import java.math.BigDecimal;

public record MonthlyRevenue(int year, int month, BigDecimal volume, BigDecimal commission) {}
