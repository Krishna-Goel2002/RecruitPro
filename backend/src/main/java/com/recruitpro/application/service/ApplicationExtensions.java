package com.recruitpro.application.service;

import com.recruitpro.application.entity.Application;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Combines every {@link ApplicationDetailExtension} bean (none until WP-BE-C: empty lists, no extras). */
@Component
public class ApplicationExtensions {

    private final ObjectProvider<ApplicationDetailExtension> extensions;

    public ApplicationExtensions(ObjectProvider<ApplicationDetailExtension> extensions) {
        this.extensions = extensions;
    }

    public Map<Long, ApplicationDetailExtension.ApplicantExtras> applicantExtras(Collection<Long> applicationIds) {
        Map<Long, ApplicationDetailExtension.ApplicantExtras> merged = new HashMap<>();
        if (applicationIds == null || applicationIds.isEmpty()) {
            return merged;
        }
        extensions.orderedStream().forEach(extension -> {
            Map<Long, ApplicationDetailExtension.ApplicantExtras> part = extension.applicantExtras(applicationIds);
            if (part == null) {
                return;
            }
            part.forEach((id, extras) -> {
                if (id != null && extras != null) {
                    merged.merge(id, extras, (a, b) -> new ApplicationDetailExtension.ApplicantExtras(
                            a.workSampleStatus() != null ? a.workSampleStatus() : b.workSampleStatus(),
                            a.interviewCount() + b.interviewCount()));
                }
            });
        });
        return merged;
    }

    public List<Object> staffWorkSamples(Application application) {
        return collect(extension -> extension.staffWorkSamples(application));
    }

    public List<Object> staffInterviews(Application application) {
        return collect(extension -> extension.staffInterviews(application));
    }

    public List<Object> candidateWorkSamples(Application application) {
        return collect(extension -> extension.candidateWorkSamples(application));
    }

    public List<Object> candidateInterviews(Application application) {
        return collect(extension -> extension.candidateInterviews(application));
    }

    private List<Object> collect(Function<ApplicationDetailExtension, List<?>> source) {
        List<Object> all = new ArrayList<>();
        extensions.orderedStream().forEach(extension -> {
            List<?> part = source.apply(extension);
            if (part != null) {
                all.addAll(part);
            }
        });
        return all;
    }
}
