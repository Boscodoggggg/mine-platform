package com.bosco.mine.server.modules.system.infrastructure;

import com.bosco.mine.server.modules.system.service.OperationsObservabilityQuery;
import com.bosco.mine.server.modules.system.service.OperationsObservabilityService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 使用固定 PromQL 模板查询系统运行历史，禁止调用方透传任意查询。
 */
public class PrometheusOperationsQuery implements OperationsObservabilityQuery {

    private static final Map<String, Target> TARGETS = Map.of(
            "mine-server", new Target("mine-server", "SERVICE", Metric.UP),
            "mine-gateway", new Target("mine-gateway", "SERVICE", Metric.UP),
            "mine-telemetry", new Target("mine-telemetry", "SERVICE", Metric.UP),
            "rabbitmq", new Target("rabbitmq", "MESSAGE", Metric.UP),
            "postgres", new Target("postgres", "STORAGE", Metric.POSTGRES_UP),
            "timescale", new Target("timescale", "STORAGE", Metric.POSTGRES_UP),
            "redis", new Target("redis", "STORAGE", Metric.REDIS_UP)
    );

    private final PrometheusProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final HttpClient httpClient;

    /**
     * 创建 Prometheus 历史查询适配器。
     *
     * @param properties   Prometheus 配置
     * @param objectMapper JSON 映射器
     * @param clock        UTC 时钟
     */
    PrometheusOperationsQuery(PrometheusProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public QueryResult query(OperationsObservabilityService.TimeRange range, Set<String> keys) {
        Instant generatedAt = clock.instant();
        if (!properties.enabled()) {
            return new QueryResult(false, generatedAt, List.of());
        }
        Set<String> allowedKeys = keys.stream()
                .filter(TARGETS::containsKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (allowedKeys.isEmpty()) {
            return new QueryResult(true, generatedAt, List.of());
        }
        Map<String, List<StatusPoint>> points = new LinkedHashMap<>();
        boolean anySuccessfulQuery = false;
        for (Map.Entry<Metric, List<Target>> entry : groupTargets(allowedKeys).entrySet()) {
            try {
                Map<String, List<StatusPoint>> queried = queryRange(
                        entry.getKey(), entry.getValue(), range, generatedAt
                );
                queried.forEach((key, values) -> points.computeIfAbsent(key, ignored -> new ArrayList<>())
                        .addAll(values));
                anySuccessfulQuery = true;
            } catch (IOException | JacksonException exception) {
                // 单组查询失败时保留其他指标，所有组都失败才将数据源标记为不可用。
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return new QueryResult(false, generatedAt, List.of());
            } catch (RuntimeException exception) {
                // 非预期上游结构不得泄露到接口响应。
            }
        }
        if (!anySuccessfulQuery) {
            return new QueryResult(false, generatedAt, List.of());
        }
        List<ItemHistory> histories = allowedKeys.stream()
                .map(TARGETS::get)
                .map(target -> new ItemHistory(
                        target.key(),
                        target.category(),
                        points.getOrDefault(target.key(), List.of()).stream()
                                .sorted(Comparator.comparing(StatusPoint::time))
                                .toList(),
                        List.of()
                ))
                .toList();
        return new QueryResult(true, generatedAt, histories);
    }

    private Map<Metric, List<Target>> groupTargets(Set<String> keys) {
        return keys.stream()
                .map(TARGETS::get)
                .collect(Collectors.groupingBy(
                        Target::metric,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
    }

    private Map<String, List<StatusPoint>> queryRange(
            Metric metric,
            List<Target> targets,
            OperationsObservabilityService.TimeRange range,
            Instant end
    ) throws IOException, InterruptedException, JacksonException {
        String promQl = promQl(metric, targets);
        long step = stepSeconds(range);
        String query = "query=" + encode(promQl)
                + "&start=" + end.minus(range.duration()).getEpochSecond()
                + "&end=" + end.getEpochSecond()
                + "&step=" + step;
        String base = properties.baseUrl().toString().replaceAll("/+$", "");
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/v1/query_range?" + query))
                .timeout(properties.requestTimeout())
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300
                || response.body().length > properties.maxResponseBytes()) {
            throw new IOException("Prometheus 查询不可用");
        }
        return parseMatrix(response.body(), targets);
    }

    private Map<String, List<StatusPoint>> parseMatrix(byte[] body, List<Target> targets) throws JacksonException {
        JsonNode root = objectMapper.readTree(body);
        if (!"success".equals(root.path("status").asText())
                || !"matrix".equals(root.path("data").path("resultType").asText())) {
            throw new IllegalArgumentException("Prometheus 返回了非矩阵结果");
        }
        Set<String> allowedJobs = targets.stream().map(Target::key).collect(Collectors.toSet());
        Map<String, List<StatusPoint>> result = new LinkedHashMap<>();
        for (JsonNode series : root.path("data").path("result")) {
            String job = series.path("metric").path("job").asText();
            if (!allowedJobs.contains(job)) {
                continue;
            }
            List<StatusPoint> values = new ArrayList<>();
            for (JsonNode value : series.path("values")) {
                if (!value.isArray() || value.size() < 2) {
                    continue;
                }
                Instant time = Instant.ofEpochSecond(value.get(0).asLong());
                double numericValue = Double.parseDouble(value.get(1).asText());
                values.add(new StatusPoint(time, numericValue > 0 ? 1 : 0));
            }
            result.put(job, values);
        }
        return result;
    }

    private String promQl(Metric metric, List<Target> targets) {
        List<String> jobs = targets.stream().map(Target::key).sorted().toList();
        String selector = jobs.size() == 1
                ? "job=\"" + jobs.getFirst() + "\""
                : "job=~\"" + String.join("|", jobs) + "\"";
        return metric.metricName + "{" + selector + "}";
    }

    private long stepSeconds(OperationsObservabilityService.TimeRange range) {
        return switch (range) {
            case HOUR, SIX_HOURS -> 60;
            case DAY -> 300;
            case WEEK -> 900;
            case FIFTEEN_DAYS -> 1800;
        };
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private enum Metric {
        UP("up"),
        POSTGRES_UP("pg_up"),
        REDIS_UP("redis_up");

        private final String metricName;

        Metric(String metricName) {
            this.metricName = metricName;
        }
    }

    private record Target(String key, String category, Metric metric) {
    }
}
