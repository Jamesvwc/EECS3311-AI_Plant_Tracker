package com.plantorganizer.cli.commands;

import java.util.LinkedHashMap;
import java.util.Map;

/** Every top-level command the CLI knows, by the name typed after "/". */
public final class Commands {

    private static final Map<String, Class<?>> ALL = new LinkedHashMap<>();

    static {
        ALL.put("identify", IdentifyCommand.class);
        ALL.put("plants", PlantsCommand.class);
        ALL.put("health", HealthCommand.class);
        ALL.put("wishlist", WishlistCommand.class);
        ALL.put("recommend", RecommendCommand.class);
        ALL.put("notifications", NotificationsCommand.class);
        ALL.put("calendar", CalendarCommand.class);
        ALL.put("spaces", SpacesCommand.class);
        ALL.put("friends", FriendsCommand.class);
        ALL.put("share", ShareCommand.class);
        ALL.put("chat", ChatCommand.class);
        ALL.put("users", UsersCommand.class);
        ALL.put("help", HelpCommand.class);
        ALL.put("quit", QuitCommand.class);
    }

    private Commands() {
    }

    /** The command class for a name typed after "/", or null. */
    public static Class<?> find(String name) {
        return ALL.get(name);
    }

    public static Map<String, Class<?>> all() {
        return ALL;
    }
}
