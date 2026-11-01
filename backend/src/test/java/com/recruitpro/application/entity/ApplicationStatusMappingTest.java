package com.recruitpro.application.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import com.recruitpro.hr.entity.Job;
import com.recruitpro.hr.entity.JobRequirement;
import com.recruitpro.candidate.entity.ResumeAnalysis;
import com.recruitpro.candidate.entity.ResumeAnalysisFact;
import com.recruitpro.user.entity.User;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ApplicationStatusMappingTest {

    @Test
    void mapsApplicationStatusToNonNullVarcharWhileKeepingEnumStrings() throws NoSuchFieldException {
        Field status = Application.class.getDeclaredField("status");

        assertEquals(EnumType.STRING, status.getAnnotation(Enumerated.class).value());
        assertEquals(SqlTypes.VARCHAR, status.getAnnotation(JdbcTypeCode.class).value());
        assertEquals(30, status.getAnnotation(Column.class).length());
        assertFalse(status.getAnnotation(Column.class).nullable());
        assertEquals(ApplicationStatus.SUBMITTED, new Application().getStatus());
    }

    @Test
    void mapsEveryOtherMigratedEnumColumnToVarchar() throws NoSuchFieldException {
        assertVarcharEnumMapping(User.class, "role", 50);
        assertVarcharEnumMapping(Job.class, "jobType", 30);
        assertVarcharEnumMapping(Job.class, "experienceLevel", 30);
        assertVarcharEnumMapping(Job.class, "status", 30);
        assertVarcharEnumMapping(JobRequirement.class, "type", 30);
    }

    @Test
    void mapsResumeIntelligenceEnumColumnsToVarchar() throws NoSuchFieldException {
        assertVarcharEnumMapping(ResumeAnalysis.class, "status", 30);
        assertVarcharEnumMapping(ResumeAnalysisFact.class, "type", 30);
        assertVarcharEnumMapping(ResumeAnalysisFact.class, "reviewStatus", 20);
    }

    private void assertVarcharEnumMapping(Class<?> entityType, String fieldName, int length)
            throws NoSuchFieldException {
        Field field = entityType.getDeclaredField(fieldName);

        assertEquals(EnumType.STRING, field.getAnnotation(Enumerated.class).value(),
                entityType.getSimpleName() + "." + fieldName + " must persist enum names");
        assertEquals(SqlTypes.VARCHAR, field.getAnnotation(JdbcTypeCode.class).value(),
                entityType.getSimpleName() + "." + fieldName + " must use JDBC VARCHAR");
        assertEquals(length, field.getAnnotation(Column.class).length(),
                entityType.getSimpleName() + "." + fieldName + " must preserve its column length");
        assertFalse(field.getAnnotation(Column.class).nullable(),
                entityType.getSimpleName() + "." + fieldName + " must remain non-null");
    }
}
