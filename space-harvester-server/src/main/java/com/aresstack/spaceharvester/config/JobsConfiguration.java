package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.jobs.InMemoryJobEventStore;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobEventStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobStore;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.jobs.RouteLookup;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Verdrahtet die Auftragsverwaltung. Bis der Lucene-Speicher (#26) einen {@link JobStore} liefert, liegen Aufträge nur
 * im Speicher und gehen beim Neustart verloren.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JobsProperties.class)
class JobsConfiguration {

    @Bean
    @ConditionalOnMissingBean(JobStore.class)
    JobStore jobStore() {
        return new InMemoryJobStore();
    }

    @Bean
    @ConditionalOnMissingBean(JobEventStore.class)
    JobEventStore jobEventStore() {
        return new InMemoryJobEventStore();
    }

    /** Proxy-Route je Quelle für die Diagnose gescheiterter Aufträge; ohne Quellenkatalog keine. */
    @Bean
    Jobs jobs(JobStore jobStore, JobEventStore jobEventStore, ObjectProvider<ConfiguredSourceCatalog> catalog,
              JobsProperties properties) {
        ConfiguredSourceCatalog sources = catalog.getIfAvailable();
        RouteLookup routes = sources == null ? RouteLookup.NONE : sources::route;
        return new ExecutorOwningJobs(jobStore, jobEventStore, routes,
                new JobLimits(properties.maxConcurrent(), properties.maxQueued(), properties.maxRunTime(),
                        properties.retention(), properties.maxOpenPerOwner()));
    }

    /**
     * Ein virtueller Thread je laufendem Auftrag; wie viele gleichzeitig laufen, begrenzt {@link Jobs}. Der Executor ist
     * bewusst keine eigene Bean, damit er nicht Springs {@code applicationTaskExecutor} verdrängt.
     */
    private static final class ExecutorOwningJobs extends Jobs {

        private final ExecutorService executor;

        ExecutorOwningJobs(JobStore store, JobEventStore events, RouteLookup routes, JobLimits limits) {
            this(store, events, routes, limits,
                    Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("job-", 0).factory()));
        }

        private ExecutorOwningJobs(JobStore store, JobEventStore events, RouteLookup routes, JobLimits limits,
                                   ExecutorService executor) {
            super(store, events, routes, executor, limits, Clock.systemUTC());
            this.executor = executor;
        }

        @Override
        public void close() {
            super.close();
            executor.shutdownNow();
        }
    }
}
