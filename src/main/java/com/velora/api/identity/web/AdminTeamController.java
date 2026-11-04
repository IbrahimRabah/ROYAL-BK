package com.velora.api.identity.web;

import com.velora.api.identity.dto.TeamMemberResponse;
import com.velora.api.identity.dto.TeamRolesRequest;
import com.velora.api.identity.security.UserPrincipal;
import com.velora.api.identity.service.TeamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin — Team", description = "Who may use the admin. Requires ROLE_ADMIN.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/admin/team")
public class AdminTeamController {

    private final TeamService teamService;

    public AdminTeamController(TeamService teamService) {
        this.teamService = teamService;
    }

    @Operation(summary = "Everyone who holds ADMIN or SEO_EDITOR")
    @GetMapping
    public List<TeamMemberResponse> members() {
        return teamService.members();
    }

    @Operation(summary = "Find an account by email", description = "404 TEAM_USER_NOT_FOUND when there is none.")
    @GetMapping("/lookup")
    public TeamMemberResponse lookup(@RequestParam String email) {
        return teamService.lookup(email);
    }

    @Operation(summary = "Set exactly which staff roles an account has",
            description = """
                    `roles`: any of ADMIN, SEO_EDITOR; an empty list removes them all. You cannot change your own
                    roles (409 TEAM_SELF_CHANGE) and the last administrator cannot be demoted (409 TEAM_LAST_ADMIN).
                    The open session of that person picks the change up at their next sign-in or token refresh.
                    """)
    @PutMapping("/{userId}/roles")
    public TeamMemberResponse setRoles(@PathVariable Long userId, @Valid @RequestBody TeamRolesRequest request,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        return teamService.setRoles(userId, request.roles(), principal.id());
    }
}
