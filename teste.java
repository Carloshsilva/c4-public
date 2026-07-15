package br.com.b3.middlewares.selicconecta.outbound.configs;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.config.TcpIpConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;

/**
 * Configuração do cluster Hazelcast usado pelo rate limiter de saída (SELIC).
 *
 * Descoberta por TCP/IP (multicast desligado): cada ambiente lista os membros
 * em 'hazelcast.members' (formato host:porta, separados por vírgula).
 *  - local: "localhost:5701,localhost:5702" (2 instâncias na mesma máquina;
 *           auto-incremento de porta permite a 2a subir na 5702)
 *  - dev/cert/qa: "servidorA:5701,servidorB:5701" (servidores separados, mesma porta)
 *
 * Sem CP Subsystem: a atomicidade do estado compartilhado usa IMap.lock.
 */
@Configuration
public class HazelcastConfig {

    @Value("${hazelcast.port:5701}")
    private int port;

    @Value("${hazelcast.members:localhost:5701,localhost:5702}")
    private String members;

    @Value("${hazelcast.cluster-name:selicconecta-ratelimit}")
    private String clusterName;

    @Bean
    public HazelcastInstance hazelcastInstance() {
        Config config = new Config();
        config.setClusterName(clusterName);

        NetworkConfig network = config.getNetworkConfig();
        network.setPort(port);
        network.setPortAutoIncrement(true); // permite 2 instâncias no mesmo host (local): 5701 -> 5702

        JoinConfig join = network.getJoin();
        join.getMulticastConfig().setEnabled(false); // ambiente controlado: sem multicast

        TcpIpConfig tcpIp = join.getTcpIpConfig();
        tcpIp.setEnabled(true);
        tcpIp.setMembers(parseMembers(members));

        return Hazelcast.newHazelcastInstance(config);
    }

    /** "hostA:5701, hostB:5701" -> ["hostA:5701", "hostB:5701"] (ignora espaços e vazios). */
    private List<String> parseMembers(String raw) {
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}

hazelcast:
  port: 5701
  cluster-name: selicconecta-ratelimit
  members: "localhost:5701,localhost:5702"   # LOCAL. Em dev/cert/qa: "servidorA:5701,servidorB:5701"
