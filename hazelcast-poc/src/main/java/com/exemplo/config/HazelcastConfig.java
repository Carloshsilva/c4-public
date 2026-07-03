package com.exemplo.config;

import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HazelcastConfig {

    @Bean
    public Config hazelcastConfig() {
        Config config = new Config();
        config.setClusterName("poc-cluster");

        NetworkConfig network = config.getNetworkConfig();
        network.setPort(5701)
               .setPortAutoIncrement(true);

        JoinConfig join = network.getJoin();
        join.getMulticastConfig().setEnabled(false);
        join.getTcpIpConfig()
            .setEnabled(true)
            .addMember("localhost:5701")
            .addMember("localhost:5702");

        return config;
    }

    @Bean
    public HazelcastInstance hazelcastInstance(Config config) {
        return Hazelcast.newHazelcastInstance(config);
    }
}
