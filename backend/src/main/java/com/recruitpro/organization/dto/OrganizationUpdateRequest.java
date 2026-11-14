package com.recruitpro.organization.dto;

import jakarta.validation.constraints.Size;

/** PATCH semantics: null fields are left unchanged. */
public record OrganizationUpdateRequest(@Size(min = 1, max = 200) String name, Boolean blindReview,
                                        Boolean shareVerificationStatus) { }
