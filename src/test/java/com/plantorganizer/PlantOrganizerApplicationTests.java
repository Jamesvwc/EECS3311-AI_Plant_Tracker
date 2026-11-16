package com.plantorganizer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:contextloads;MODE=PostgreSQL",
		"plantorganizer.scheduling.enabled=false"})
class PlantOrganizerApplicationTests {

	@Test
	void contextLoads() {
	}

}
