package com.velora.api.identity.service;

import com.velora.api.audit.domain.AuditAction;
import com.velora.api.audit.service.AuditService;
import com.velora.api.common.exception.BusinessException;
import com.velora.api.common.exception.ErrorCode;
import com.velora.api.identity.domain.AppUser;
import com.velora.api.identity.domain.Role;
import com.velora.api.identity.dto.TeamMemberResponse;
import com.velora.api.identity.repository.AppUserRepository;
import com.velora.api.identity.repository.RoleRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who may use the admin. The owner gives each person their own account the ADMIN or SEO_EDITOR role instead of
 * sharing a login, so every change in the audit log carries a real name and one person can be removed alone.
 *
 * <p>Two rules protect the shop from locking itself out: nobody changes their OWN roles, and the last
 * administrator cannot be demoted. A role change reaches the person's session at their next sign-in or token
 * refresh, because roles travel inside the access token.
 */
@Service
public class TeamService {

    public static final String SEO_EDITOR = "SEO_EDITOR";
    static final Set<String> STAFF_ROLES = Set.of(Role.ADMIN, SEO_EDITOR);

    private final AppUserRepository userRepository;
    private final RoleRepository roleRepository;
    private final AuditService auditService;

    public TeamService(AppUserRepository userRepository, RoleRepository roleRepository, AuditService auditService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<TeamMemberResponse> members() {
        return userRepository.findByRoleCodes(STAFF_ROLES).stream().map(TeamService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public TeamMemberResponse lookup(String email) {
        return toResponse(userRepository.findByEmailIgnoreCase(email == null ? "" : email.trim())
                .orElseThrow(() -> new BusinessException(ErrorCode.TEAM_USER_NOT_FOUND)));
    }

    @Transactional
    public TeamMemberResponse setRoles(Long userId, List<String> requested, Long actorId) {
        if (userId.equals(actorId)) {
            throw new BusinessException(ErrorCode.TEAM_SELF_CHANGE);
        }
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TEAM_USER_NOT_FOUND));

        Set<String> wanted = new TreeSet<>();
        for (String code : requested) {
            String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
            if (!STAFF_ROLES.contains(normalized)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "Only ADMIN and SEO_EDITOR can be granted here");
            }
            wanted.add(normalized);
        }
        Set<String> before = staffRolesOf(user);

        boolean losesAdmin = before.contains(Role.ADMIN) && !wanted.contains(Role.ADMIN);
        if (losesAdmin && userRepository.findByRoleCodes(Set.of(Role.ADMIN)).size() <= 1) {
            throw new BusinessException(ErrorCode.TEAM_LAST_ADMIN);
        }

        user.getRoles().removeIf(r -> STAFF_ROLES.contains(r.getCode()));
        for (String code : wanted) {
            user.addRole(roleRepository.findByCode(code)
                    .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "Role missing: " + code)));
        }
        user.setStaff(!wanted.isEmpty());
        userRepository.save(user);

        if (!before.equals(wanted)) {
            auditService.record(AuditAction.PERMISSION_CHANGED, "APP_USER", user.getId(), user.getEmail(),
                    String.join(",", before), String.join(",", wanted), null, actorId);
        }
        return toResponse(user);
    }

    private static Set<String> staffRolesOf(AppUser user) {
        Set<String> out = new TreeSet<>();
        user.getRoles().forEach(r -> {
            if (STAFF_ROLES.contains(r.getCode())) {
                out.add(r.getCode());
            }
        });
        return out;
    }

    private static TeamMemberResponse toResponse(AppUser u) {
        return new TeamMemberResponse(u.getId(), u.getEmail(), u.getFullName(), new ArrayList<>(staffRolesOf(u)));
    }
}
