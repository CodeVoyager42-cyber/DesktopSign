package com.example.signer.server.db;

import org.springframework.orm.jpa.JpaTransactionManager;
import com.zaxxer.hikari.HikariDataSource;

import org.flywaydb.core.Flyway;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import jakarta.persistence.EntityManagerFactory;

import javax.sql.DataSource;

import org.hibernate.jpa.HibernatePersistenceProvider;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PostgreSQL persistence for the coordination server (brief Phase 1/§4-§7).
 *
 * <p>Active only when {@code signature.remote-server.database.enabled=true}
 * (env {@code SIGNER_DB_ENABLED}). Boot's own JPA/DataSource/Flyway
 * auto-configurations are statically excluded in {@code application.yml}, so
 * this class is the single, deterministic path to a DataSource: the desktop
 * context and the file-based server mode never see one.
 *
 * <p>Credentials come exclusively from the environment
 * ({@code SIGNER_DB_URL}, {@code SIGNER_DB_USERNAME}, {@code SIGNER_DB_PASSWORD})
 * — never from committed configuration (brief §6).
 *
 * <p>On startup Flyway applies {@code classpath:db/migration} before any
 * repository is used; Hibernate validates nothing (ddl-auto none) — the
 * schema is owned by the migrations.
 */
@Configuration
@ConditionalOnProperty(name = "signature.remote-server.database.enabled", havingValue = "true")
@EnableTransactionManagement
@EnableJpaRepositories(
        basePackages = "com.example.signer.server.db.repository",
        entityManagerFactoryRef = "signerEntityManagerFactory",
        transactionManagerRef = "signerTransactionManager")
public class DbPersistenceConfiguration {

    /** Database connection settings, bound from {@code signature.remote-server.database.*}. */
    @ConfigurationProperties(prefix = "signature.remote-server.database")
    @Bean
    public DatabaseSettings databaseSettings() {
        return new DatabaseSettings();
    }

    /** Connection pool; credentials come from environment-backed settings. */
    @Bean
    public DataSource signerDataSource(DatabaseSettings settings) {
        HikariDataSource ds = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .driverClassName("org.postgresql.Driver")
                .url(settings.getUrl())
                .username(settings.getUsername())
                .password(settings.getPassword())
                .build();
        ds.setMaximumPoolSize(10);
        ds.setPoolName("signer-server-db");
        return ds;
    }

    /** Applies the Flyway migrations synchronously at startup (brief §5). */
    @Bean(initMethod = "migrate")
    public Flyway signerFlyway(DataSource signerDataSource) {
        return Flyway.configure()
                .dataSource(signerDataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .load();
    }

    @Bean
    public LocalContainerEntityManagerFactoryBean signerEntityManagerFactory(
            DataSource signerDataSource) {
        LocalContainerEntityManagerFactoryBean emf =
                new LocalContainerEntityManagerFactoryBean();
        emf.setDataSource(signerDataSource);
        emf.setPackagesToScan("com.example.signer.server.db.entity");
        emf.setPersistenceProvider(new HibernatePersistenceProvider());
        emf.setJpaVendorAdapter(new HibernateJpaVendorAdapter());

        Map<String, Object> jpa = new LinkedHashMap<>();
        jpa.put("hibernate.hbm2ddl.auto", "none");   // schema owned by Flyway
        jpa.put("hibernate.jdbc.time_zone", "UTC");
        emf.setJpaPropertyMap(jpa);
        return emf;
    }

    @Bean
    public PlatformTransactionManager signerTransactionManager(
            EntityManagerFactory signerEntityManagerFactory) {
        return new JpaTransactionManager(
                signerEntityManagerFactory);
    }

    /** Simple binding of the database connection settings. */
    public static class DatabaseSettings {
        private String url = "jdbc:postgresql://localhost:5432/signer";
        private String username = "postgres";
        private String password = "";

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
