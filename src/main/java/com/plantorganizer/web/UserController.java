package com.plantorganizer.web;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.plantorganizer.agent.AgentController;
import com.plantorganizer.common.CurrentUser;

/**
 * Users for the Profile page. There is no login (out of scope), so this just
 * lists and creates users; the browser picks which one it acts as.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final AgentController agent;

    public UserController(AgentController agent) {
        this.agent = agent;
    }

    public record UserView(long id, String displayName) {
    }

    public record CreateUserRequest(String displayName) {
    }

    @GetMapping
    public List<UserView> list() {
        return agent.listUsers().stream().map(u -> new UserView(u.getId(), u.getDisplayName())).toList();
    }

    @GetMapping("/me")
    public UserView me(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId) {
        var u = agent.getUser(userId);
        return new UserView(u.getId(), u.getDisplayName());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public UserView create(@RequestBody CreateUserRequest r) {
        var u = agent.createUser(r.displayName());
        return new UserView(u.getId(), u.getDisplayName());
    }
}
