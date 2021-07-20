package com.provectus.kafka.ui.strategy.ksqlStatement;

import com.fasterxml.jackson.databind.JsonNode;
import com.provectus.kafka.ui.model.KsqlCommand;
import com.provectus.kafka.ui.model.KsqlCommandResponse;
import com.provectus.kafka.ui.model.Table;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public abstract class KsqlStatementStrategy {
    protected String host = null;
    protected KsqlCommand ksqlCommand = null;

    public String getUri() {
        if (this.host != null) {
            return this.host + this.getRequestPath();
        }
        return null;
    }

    public boolean test(String sql) {
        return sql.trim().toLowerCase().matches(getTestRegExp());
    }

    public KsqlStatementStrategy host(String host) {
        this.host = host;
        return this;
    }

    public KsqlCommand getKsqlCommand() {
        return ksqlCommand;
    }

    public KsqlStatementStrategy ksqlCommand(KsqlCommand ksqlCommand) {
        this.ksqlCommand = ksqlCommand;
        return this;
    }

    protected KsqlCommandResponse serializeTableResponse(JsonNode response, String path) {
        if (response.isArray() && response.size() > 0) {
            KsqlCommandResponse commandResponse = new KsqlCommandResponse();
            JsonNode first = response.get(0);
            JsonNode items = first.path(path);
            Table table = items.isArray() ? getTableFromArray(items) : getTableFromObject(items);
            return commandResponse.data(table);
        }
        throw new InternalError("Invalid data format");
    }

    protected KsqlCommandResponse serializeMessageResponse(JsonNode response, String path) {
        if (response.isArray() && response.size() > 0) {
            KsqlCommandResponse commandResponse = new KsqlCommandResponse();
            JsonNode first = response.get(0);
            JsonNode item = first.path(path);
            return commandResponse.message(getMessageFromObject(item));
        }
        // TODO: handle
        throw new InternalError("Invalid data format");
    }

    protected KsqlCommandResponse serializeQueryResponse(JsonNode response) {
        KsqlCommandResponse commandResponse = new KsqlCommandResponse();
        Table table = (new Table())
                .headers(getQueryResponseHeader(response))
                .rows(getQueryResponseRows(response));
        return commandResponse.data(table);
    }

    private List<String> getQueryResponseHeader(JsonNode response) {
        JsonNode headerRow = response.get(0);
        if (headerRow.isObject() && headerRow.size() > 0) {
            String schema = headerRow.get("header").get("schema").asText();
            return Arrays.stream(schema.split(",")).map(s -> s.trim()).collect(Collectors.toList());
        }
        return new ArrayList<>();
    }

    private List<List<String>> getQueryResponseRows(JsonNode node) {
        return getStreamForJsonArray(node)
                .filter(row -> row.has("row") && row.get("row").has("columns"))
                .map(row -> row.get("row").get("columns"))
                .map(cellNode -> getStreamForJsonArray(cellNode)
                                .map(cell -> cell.asText())
                                .collect(Collectors.toList())
                )
                .collect(Collectors.toList());
    }

    private Table getTableFromArray(JsonNode node) {
        Table table = new Table();
        table.headers(new ArrayList<>()).rows(new ArrayList<>());
        if (node.size() > 0) {
            List<String> keys = getJsonObjectKeys(node.get(0));
            List<List<String>> rows = getTableRows(node, keys);
            table.headers(keys).rows(rows);
        }
        return table;
    }

    private Table getTableFromObject(JsonNode node) {
        List<String> keys = getJsonObjectKeys(node);
        List<String> values = getJsonObjectValues(node);
        List<List<String>> rows = IntStream
                .range(0, keys.size())
                .mapToObj(i -> List.of(keys.get(i), values.get(i)))
                .collect(Collectors.toList());
        return (new Table()).headers(List.of("key", "value")).rows(rows);
    }

    private String getMessageFromObject(JsonNode node) {
        if (node.isObject() && node.has("message")) {
            return node.get("message").asText();
        }
        throw new InternalError("can't get message from empty object or array");
    }

    private List<List<String>> getTableRows(JsonNode node, List<String> keys) {
        return getStreamForJsonArray(node)
                .map(row -> keys.stream()
                        .map(header -> row.get(header).asText())
                        .collect(Collectors.toList())
                )
                .collect(Collectors.toList());
    }

    private Stream<JsonNode> getStreamForJsonArray(JsonNode node) {
        if (node.isArray() && node.size() > 0) {
            return StreamSupport.stream(node.spliterator(), false);
        }
        // TODO: handle
        throw new InternalError("not JsonArray or empty");
    }

    private List<String> getJsonObjectKeys(JsonNode node) {
        if (node.isObject()) {
            return StreamSupport.stream(
                    Spliterators.spliteratorUnknownSize(node.fieldNames(), Spliterator.ORDERED), false
            ).collect(Collectors.toList());
        }
        // TODO: handle
        throw new InternalError("Invalid data format");
    }

    private List<String> getJsonObjectValues(JsonNode node) {
        return getJsonObjectKeys(node).stream().map(key -> node.get(key).asText())
                .collect(Collectors.toList());
    }

    public abstract KsqlCommandResponse serializeResponse(JsonNode response);

    protected abstract String getRequestPath();

    protected abstract String getTestRegExp();
}
