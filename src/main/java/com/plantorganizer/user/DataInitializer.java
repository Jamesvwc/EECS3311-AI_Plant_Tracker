package com.plantorganizer.user;

import java.time.Clock;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Creates the default user ("Me", id 1) the first time the app starts with an empty database. */
@Component
public class DataInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final Clock clock;

    public DataInitializer(UserRepository userRepository, Clock clock) {
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.count() == 0) {
            userRepository.save(new User("Me", clock.instant()));
        }
    }
}
