package com.patientcard.patient_card_backend.config;

import com.mongodb.AuthenticationMechanism;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;

import java.util.Collections;

@Configuration
public class SmartCareMongoConfig {

    @Bean(destroyMethod = "close")
    @Primary
    public MongoClient mongoClient(
            @Value("${mongo.host}") String host,
            @Value("${mongo.port}") int port,
            @Value("${mongo.username:}") String username,
            @Value("${mongo.password:}") String password,
            @Value("${mongo.authSource:${mongo.database}}") String authSource,
            @Value("${mongo.authMechanism:SCRAM-SHA-256}") String authMechanism) {
        MongoClientSettings.Builder builder = MongoClientSettings.builder()
                .applyToClusterSettings(settings -> settings.hosts(
                        Collections.singletonList(new ServerAddress(host, port))));
        if (username != null && !username.trim().isEmpty()) {
            MongoCredential credential = MongoCredential
                    .createCredential(username.trim(), authSource, password.toCharArray())
                    .withMechanism(AuthenticationMechanism.fromMechanismName(authMechanism));
            builder.credential(credential);
        }
        return MongoClients.create(builder.build());
    }

    @Bean
    @Primary
    public MongoDatabaseFactory mongoDatabaseFactory(
            MongoClient mongoClient,
            @Value("${mongo.database}") String database) {
        return new SimpleMongoClientDatabaseFactory(mongoClient, database);
    }

    @Bean
    @Primary
    public MongoTemplate mongoTemplate(MongoDatabaseFactory mongoDatabaseFactory) {
        return new MongoTemplate(mongoDatabaseFactory);
    }

    /**
     * DataCenter（VI_ICU_ZYYZ 隔离/抢救医嘱）第二库。
     * mongo.center.database 留空则不创建，隔离与夜间抢救功能降级为不可用。
     * host/port/凭证缺省跟随主库配置。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(value = "mongo.center.database")
    public MongoClient centerMongoClient(
            @Value("${mongo.center.host:${mongo.host}}") String host,
            @Value("${mongo.center.port:${mongo.port}}") int port,
            // 凭证同样缺省跟随主库；仅显式置空（mongo.center.username: ""）才退化为免认证连接
            @Value("${mongo.center.username:${mongo.username}}") String username,
            @Value("${mongo.center.password:${mongo.password}}") String password,
            @Value("${mongo.center.authSource:${mongo.center.database}}") String authSource,
            @Value("${mongo.center.authMechanism:SCRAM-SHA-1}") String authMechanism) {
        boolean auth = username != null && !username.trim().isEmpty();
        LoggerFactory.getLogger(SmartCareMongoConfig.class).info(
                "DataCenter Mongo: host={}, port={}, username={}, authSource={}, authMechanism={}, auth={}",
                host, port, auth ? username.trim() : "(none)", authSource, authMechanism, auth);
        MongoClientSettings.Builder builder = MongoClientSettings.builder()
                .applyToClusterSettings(settings -> settings.hosts(
                        Collections.singletonList(new ServerAddress(host, port))));
        if (auth) {
            MongoCredential credential = MongoCredential
                    .createCredential(username.trim(), authSource, password.toCharArray());
            if (authMechanism != null && !authMechanism.trim().isEmpty()) {
                credential = credential.withMechanism(AuthenticationMechanism.fromMechanismName(authMechanism.trim()));
            }
            builder.credential(credential);
        }
        return MongoClients.create(builder.build());
    }

    @Bean
    @ConditionalOnProperty(value = "mongo.center.database")
    public MongoTemplate centerMongoTemplate(
            @Qualifier("centerMongoClient") MongoClient centerMongoClient,
            @Value("${mongo.center.database}") String centerDatabase) {
        return new MongoTemplate(
                new SimpleMongoClientDatabaseFactory(centerMongoClient, centerDatabase));
    }
}
