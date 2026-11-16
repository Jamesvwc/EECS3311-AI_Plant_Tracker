package com.plantorganizer.user;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.plantorganizer.common.NotFoundException;
import com.plantorganizer.sharing.ShareLink;
import com.plantorganizer.sharing.ShareLinkJpaRepository;

/**
 * Stage 1 UserRepository: saves and loads users, friendships and share links.
 * Spring Data JPA writes the SQL (replacing Stage 1's DatabaseManager).
 */
@Repository
public class UserRepository {

    private final UserJpaRepository users;
    private final ShareLinkJpaRepository shareLinks;

    public UserRepository(UserJpaRepository users, ShareLinkJpaRepository shareLinks) {
        this.users = users;
        this.shareLinks = shareLinks;
    }

    public User save(User user) {
        return users.save(user);
    }

    public User findById(long id) {
        return users.findById(id).orElseThrow(() -> new NotFoundException("User " + id + " does not exist."));
    }

    public List<User> findAll() {
        return users.findAll();
    }

    public long count() {
        return users.count();
    }

    public ShareLink saveShareLink(ShareLink link) {
        return shareLinks.save(link);
    }

    public Optional<ShareLink> findShareLink(String token) {
        return shareLinks.findByToken(token);
    }

    public List<ShareLink> findShareLinks(long ownerId) {
        return shareLinks.findByOwnerIdOrderByCreatedAtDesc(ownerId);
    }

    public void deleteShareLink(ShareLink link) {
        shareLinks.delete(link);
    }
}
