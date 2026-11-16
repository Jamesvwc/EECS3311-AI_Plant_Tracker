package com.plantorganizer.user;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data generates the SQL for this interface; UserRepository wraps it. */
public interface UserJpaRepository extends JpaRepository<User, Long> {
}
