package com.plantorganizer.cli;

import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * Everything the commands share while Agent.jar runs: the server connection,
 * the console, who the current user is, and what the last commands produced
 * that a later command can refer to (the plants /identify found, so
 * {@code /identify select 2} works; the plants /recommend ask suggested, so
 * {@code /recommend keep "Spider plant"} works).
 */
public final class Session {

    /** A plant /identify found, which /identify select can pick. */
    public record Candidate(String scientificName, String commonName, double score) {

        public String displayName() {
            return commonName != null ? commonName + " (" + scientificName + ")" : scientificName;
        }
    }

    private final AgentClient api;
    private final CliConsole console;
    private final Resolver resolver;
    private CommandDispatcher dispatcher;
    private String userName;
    private List<Candidate> identified = List.of();
    private List<JsonNode> suggestedRecommendations = List.of();
    private boolean quitRequested;
    private boolean preConfirmed;

    public Session(AgentClient api, CliConsole console, String userName) {
        this.api = api;
        this.console = console;
        this.userName = userName;
        this.resolver = new Resolver(api, this);
    }

    public AgentClient api() {
        return api;
    }

    public CliConsole console() {
        return console;
    }

    public Resolver find() {
        return resolver;
    }

    public CommandDispatcher dispatcher() {
        return dispatcher;
    }

    void setDispatcher(CommandDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    public long userId() {
        return api.userId();
    }

    public String userName() {
        return userName;
    }

    public String prompt() {
        return "agent[" + userName + "]> ";
    }

    /** Acts as another user from now on; what the previous user's commands found is forgotten. */
    public void switchUser(long userId, String userName) {
        api.setUserId(userId);
        this.userName = userName;
        identified = List.of();
        suggestedRecommendations = List.of();
    }

    public List<Candidate> identified() {
        return identified;
    }

    public void setIdentified(List<Candidate> candidates) {
        identified = List.copyOf(candidates);
    }

    public List<JsonNode> suggestedRecommendations() {
        return suggestedRecommendations;
    }

    public void setSuggestedRecommendations(List<JsonNode> recommendations) {
        suggestedRecommendations = List.copyOf(recommendations);
    }

    /**
     * Asks the user to confirm an addition or deletion. A command the user
     * already approved as a whole (one the agent suggested, then "Run it?")
     * is not asked about twice.
     */
    public boolean confirm(String question) {
        return preConfirmed || console.confirm(question);
    }

    /** Runs a command line the user just approved, without asking for confirmation again. */
    public void runApproved(String commandLine) {
        boolean before = preConfirmed;
        preConfirmed = true;
        try {
            dispatcher.execute(commandLine, false);
        } finally {
            preConfirmed = before;
        }
    }

    public boolean quitRequested() {
        return quitRequested;
    }

    public void requestQuit() {
        quitRequested = true;
    }
}
