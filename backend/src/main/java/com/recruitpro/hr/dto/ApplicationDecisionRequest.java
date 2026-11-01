package com.recruitpro.hr.dto;

import jakarta.validation.constraints.Size;

public record ApplicationDecisionRequest(@Size(max = 2000) String comment) { }
