package com.recruitpro.hr.dto;

import jakarta.validation.constraints.Size;

public record ApplicationCommentRequest(@Size(max = 2000) String comment) { }
