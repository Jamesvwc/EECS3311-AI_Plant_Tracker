package com.plantorganizer.sharing;

/**
 * One friend in the Friends page.
 *
 * @param canSeeMyPlants whether I allow this friend to view my plant list
 * @param sharesWithMe   whether this friend allows me to view theirs
 */
public record FriendView(long id, String name, boolean canSeeMyPlants, boolean sharesWithMe) {
}
