package com.lky.nexus.app.infra.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.Jackson3JsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

/**
 * Elasticsearch 官方 Java Client 的装配（ADR-006：BM25 用原生客户端）。
 *
 * <p>三个已核实的版本细节（ES 9 与 8 差别不小，逐一用 javap 确认过）：
 * <ol>
 *   <li>客户端版本与 ES 服务端<b>严格同版本</b>（9.4.5）：官方客户端会做版本兼容性检查，
 *       跨大版本直接被拒绝；</li>
 *   <li>ES 9 的传输层换成 {@code elasticsearch-rest5-client}，
 *       入口是 {@code Rest5Client.builder(URI...)}（8.x 时代是 HttpHost + RestClient）；</li>
 *   <li>客户端 9.4.5 提供了 {@code Jackson3JsonpMapper}，可以直接复用 Spring Boot 4 的
 *       Jackson 3 {@link JsonMapper}——不必再引入 Jackson 2 或额外的 JSON-P 提供者，
 *       整个项目的 JSON 栈保持一套。</li>
 * </ol>
 */
@Configuration
@ConditionalOnProperty(name = "nexus.search.enabled", havingValue = "true")
public class ElasticsearchConfig {

    @Bean(destroyMethod = "close")
    public Rest5Client rest5Client(@Value("${nexus.search.uris}") String uris) {
        // 支持配置多个节点（逗号分隔）：单节点也能跑，但客户端具备故障转移能力
        List<URI> hosts = Arrays.stream(uris.split(","))
                .map(String::trim)
                .filter(uri -> !uri.isEmpty())
                .map(URI::create)
                .toList();
        return Rest5Client.builder(hosts.toArray(URI[]::new)).build();
    }

    @Bean(destroyMethod = "close")
    public ElasticsearchTransport elasticsearchTransport(Rest5Client rest5Client, JsonMapper jsonMapper) {
        return new Rest5ClientTransport(rest5Client, new Jackson3JsonpMapper(jsonMapper));
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(ElasticsearchTransport transport) {
        return new ElasticsearchClient(transport);
    }
}
