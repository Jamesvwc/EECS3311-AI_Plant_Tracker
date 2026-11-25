package com.plantorganizer.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.plantorganizer.agent.AgentController;
import com.plantorganizer.agent.ChatReply;
import com.plantorganizer.agent.SuggestedAction;
import com.plantorganizer.common.CurrentUser;
import com.plantorganizer.memory.ChatMessage;

/**
 * HTTP boundary of the agent's chat. The GUI chat box on every page
 * (JS/chat.js) posts to {@code /api/chat} and {@code /api/chat/action}: a
 * message never changes data by itself; a requested change comes back as a
 * proposal whose APPROVE button makes it. The terminal CLI (Agent.jar, Stage
 * 1's CliConsole) uses the read-only {@code /api/chat/ask} and
 * {@code /api/chat/interpret}.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final AgentController agent;

    public ChatController(AgentController agent) {
        this.agent = agent;
    }

    public record ActionRequest(String type, Map<String, Object> payload) {
    }

    public record AskRequest(String message) {
    }

    public record InterpretRequest(String text) {
    }

    /** Read-only chat (Agent.jar /chat): answers questions; for changes it returns a suggestion instead. */
    @PostMapping(path = "/ask", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ChatReply ask(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                         @RequestBody AskRequest r) {
        return agent.askAgent(userId, r.message());
    }

    /** Which action does this text ask for? Runs nothing and stores nothing (Agent.jar "did you mean"). */
    @PostMapping(path = "/interpret", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SuggestedAction interpret(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                                     @RequestBody InterpretRequest r) {
        return agent.interpretRequest(userId, r.text());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ChatReply send(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                          @RequestParam(name = "message", required = false) String message,
                          @RequestParam(name = "photo", required = false) MultipartFile photo) {
        return agent.chat(userId, message, Uploads.toImage(photo));
    }

    @PostMapping(path = "/action", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ChatReply action(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId,
                            @RequestBody ActionRequest r) {
        return agent.chatAction(userId, r.type(), r.payload());
    }

    @GetMapping("/history")
    public List<ChatMessage> history(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId) {
        return agent.chatHistory(userId);
    }

    @DeleteMapping("/history")
    public void clear(@RequestHeader(name = CurrentUser.HEADER, defaultValue = CurrentUser.DEFAULT_ID) long userId) {
        agent.clearChat(userId);
    }
}
