package com.plantorganizer.web;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.plantorganizer.agent.AgentController;
import com.plantorganizer.common.CurrentUser;
import com.plantorganizer.sharing.FriendView;
import com.plantorganizer.sharing.PlantSummary;

/** HTTP boundary for F11 (friends). */
@RestController
@RequestMapping("/api/friends")
public class FriendController {

    private final AgentController agent;

    public FriendController(AgentController agent) {
        this.agent = agent;
    }

    public record AddFriendRequest(Long friendId) {
    }

    public record AllowRequest(Boolean allowed) {
    }

    @GetMapping
    public List<FriendView> list(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId) {
        return agent.listFriends(userId);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public FriendView add(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                          @RequestBody AddFriendRequest r) {
        if (r.friendId() == null) {
            throw new com.plantorganizer.common.InvalidInputException("Choose a friend to add.");
        }
        return agent.addFriend(userId, r.friendId());
    }

    @DeleteMapping("/{friendId}")
    public void remove(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                       @PathVariable long friendId) {
        agent.removeFriend(userId, friendId);
    }

    @PutMapping(path = "/{friendId}/can-view", consumes = MediaType.APPLICATION_JSON_VALUE)
    public FriendView allow(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                            @PathVariable long friendId, @RequestBody AllowRequest r) {
        return agent.setFriendCanView(userId, friendId, Boolean.TRUE.equals(r.allowed()));
    }

    @GetMapping("/{friendId}/plants")
    public List<PlantSummary> plants(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                                     @PathVariable long friendId) {
        return agent.viewFriendPlants(userId, friendId);
    }
}
