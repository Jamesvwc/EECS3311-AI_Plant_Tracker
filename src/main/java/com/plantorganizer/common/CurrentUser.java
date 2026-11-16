package com.plantorganizer.common;

/**
 * The app has no login (out of scope for Stage 1/2), so the browser says which
 * user it is acting as with this request header. The default user (id 1,
 * "Me") is created at start-up; the Profile page lets you create and switch to
 * other users, which is how F11/F12 (friends, sharing) can be demonstrated.
 */
public final class CurrentUser {

    public static final String HEADER = "X-User-Id";
    public static final String DEFAULT_ID = "1";

    private CurrentUser() {
    }
}
