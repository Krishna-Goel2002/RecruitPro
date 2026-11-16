package com.recruitpro.assistant.tool;

import com.recruitpro.assistant.service.Deidentifier;
import com.recruitpro.security.CurrentUser;

/**
 * What a tool runs with: the signed-in user (the services check the role and organisation again) and the message's
 * {@link Deidentifier}, which tools feed with the names of the candidates they touch and use to quote
 * candidate-supplied text.
 */
public record ToolContext(CurrentUser user, Deidentifier deidentifier) {
}
