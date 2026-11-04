package com.velora.api.identity.dto;

import java.util.List;

/** An account and the staff roles it holds (ADMIN, SEO_EDITOR). */
public record TeamMemberResponse(Long id, String email, String name, List<String> roles) {
}
