package com.pv.cases.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.pv.cases.service.MergeService;

/**
 * Registers services that are deliberately kept free of Spring annotations.
 *
 * <p>{@link MergeService} is pure input-to-output logic constructed directly in
 * its tests, so it carries no {@code @Service} of its own; declaring it here is
 * what makes it injectable without giving the class a framework dependency.
 */
@Configuration
public class ServiceConfig {

	@Bean
	public MergeService mergeService() {
		return new MergeService();
	}
}
