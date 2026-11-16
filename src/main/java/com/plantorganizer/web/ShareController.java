package com.plantorganizer.web;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.plantorganizer.agent.AgentController;
import com.plantorganizer.common.CurrentUser;
import com.plantorganizer.sharing.ShareLinkView;
import com.plantorganizer.sharing.SharedScheduleView;

/**
 * HTTP boundary for F12. GET /api/share/{token} is the public endpoint a guest
 * without an account opens (through share.html); it needs no user header.
 */
@RestController
@RequestMapping("/api/share")
public class ShareController {

    private final AgentController agent;

    public ShareController(AgentController agent) {
        this.agent = agent;
    }

    public record CreateLinkRequest(List<Long> plantIds, String label) {
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ShareLinkView create(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                                @RequestBody CreateLinkRequest r) {
        return agent.createShareLink(userId, r.plantIds(), r.label());
    }

    @GetMapping
    public List<ShareLinkView> mine(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId) {
        return agent.listShareLinks(userId);
    }

    @DeleteMapping("/{token}")
    public void revoke(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                       @PathVariable String token) {
        agent.revokeShareLink(userId, token);
    }

    @GetMapping("/{token}")
    public SharedScheduleView open(@PathVariable String token) {
        return agent.openShareLink(token);
    }
}
