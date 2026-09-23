/** (C) Copyright 2026 Starlight. All rights reserved. */
package org.starlight.terracotta;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class TerracottaNodeList {
    private static final String NODE_LIST_URL = "https://terracotta.glavo.site/nodes";

    private record TerracottaNode(
            @SerializedName("url") String url,
            @SerializedName("region") String region
    ) {
        private void validate() {
            if (url == null || url.isBlank()) {
                throw new JsonParseException("TerracottaNode.url cannot be null");
            }
            try {
                new URI(url);
            } catch (URISyntaxException e) {
                throw new JsonParseException("Invalid URL: " + url, e);
            }
        }
    }

    private static volatile List<URI> list;

    public static List<URI> fetch() {
        List<URI> local = list;
        if (local != null) return local;

        synchronized (TerracottaNodeList.class) {
            local = list;
            if (local != null) return local;

            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(NODE_LIST_URL))
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    System.err.println("Failed to fetch terracotta node list: HTTP " + response.statusCode());
                    list = List.of();
                    return list;
                }

                // 手写解析节点列表（避免 Gson 反射解析 record，Native Image 下不可靠）
                List<TerracottaNode> nodes = new ArrayList<>();
                JsonArray arr = JsonParser.parseString(response.body()).getAsJsonArray();
                for (JsonElement el : arr) {
                    JsonObject o = el.getAsJsonObject();
                    String url = o.has("url") && !o.get("url").isJsonNull() ? o.get("url").getAsString() : null;
                    String region = o.has("region") && !o.get("region").isJsonNull() ? o.get("region").getAsString() : null;
                    nodes.add(new TerracottaNode(url, region));
                }

                boolean isChina = Locale.getDefault().getCountry().equalsIgnoreCase("CN");

                if (nodes == null || nodes.isEmpty()) {
                    list = List.of();
                    System.out.println("No available Terracotta nodes found");
                } else {
                    list = nodes.stream()
                            .filter(node -> {
                                if (node == null) return false;
                                try {
                                    node.validate();
                                } catch (Exception e) {
                                    System.err.println("Invalid terracotta node: " + node + " - " + e.getMessage());
                                    return false;
                                }
                                return node.region() == null || node.region().isBlank()
                                        || isChina == "CN".equalsIgnoreCase(node.region());
                            })
                            .map(node -> URI.create(node.url()))
                            .toList();
                    System.out.println("Terracotta node list: " + list);
                }
            } catch (Exception e) {
                System.err.println("Failed to fetch terracotta node list: " + e.getMessage());
                list = List.of();
            }

            return list;
        }
    }

    private TerracottaNodeList() {
    }
}
