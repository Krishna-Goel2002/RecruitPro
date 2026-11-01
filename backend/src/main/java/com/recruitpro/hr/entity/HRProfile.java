package com.recruitpro.hr.entity;

import com.recruitpro.common.entity.BaseEntity;
import com.recruitpro.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "hr_profiles")
@Getter @Setter @NoArgsConstructor
public class HRProfile extends BaseEntity {
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "employee_id", unique = true, length = 100)
    private String employeeId;

    @Column(length = 100)
    private String department;

    @Column(length = 100)
    private String designation;
}
