/** (C) Copyright 2026 Starlight. All rights reserved. */
package org.starlight.terracotta;

import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 支持多语言的文本对象，可同时处�?String �?对象两种 JSON 格式�? * <ul>
 *   <li>String 格式: "文本"</li>
 *   <li>对象格式: {"zh_CN": "中文文本", "default": "Default Text"}</li>
 * </ul>
 */
@JsonAdapter(LocalizedText.Adapter.class)
public final class LocalizedText {
    private final String value;
    private final Map<String, String> localizedValues;

    public LocalizedText(String value) {
        this.value = value;
        this.localizedValues = null;
    }

    public LocalizedText(Map<String, String> localizedValues) {
        this.value = null;
        this.localizedValues = Objects.requireNonNull(localizedValues);
    }

    /**
     * 根据当前系统默认 Locale 获取文本�?     */
    public String getText() {
        return getText(Locale.getDefault());
    }

    /**
     * 根据指定 Locale 获取文本�?     */
    public String getText(Locale locale) {
        if (localizedValues != null) {
            // 尝试精确匹配语言_国家，如 zh_CN
            String langKey = locale.getLanguage() + "_" + locale.getCountry();
            String text = localizedValues.get(langKey);
            if (text != null) return text;

            // 尝试只匹配语言，如 zh
            text = localizedValues.get(locale.getLanguage());
            if (text != null) return text;

            // 回退�?default
            text = localizedValues.get("default");
            if (text != null) return text;

            // 返回第一个非空值
            return localizedValues.values().stream()
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse("");
        }
        return value != null ? value : "";
    }

    @Override
    public String toString() {
        return getText();
    }

    static final class Adapter extends TypeAdapter<LocalizedText> {
        @Override
        public LocalizedText read(JsonReader jsonReader) throws IOException {
            JsonToken nextToken = jsonReader.peek();
            if (nextToken == JsonToken.NULL) {
                jsonReader.nextNull();
                return null;
            } else if (nextToken == JsonToken.STRING) {
                return new LocalizedText(jsonReader.nextString());
            } else if (nextToken == JsonToken.BEGIN_OBJECT) {
                LinkedHashMap<String, String> localizedValues = new LinkedHashMap<>();

                jsonReader.beginObject();
                while (jsonReader.hasNext()) {
                    String name = jsonReader.nextName();
                    String value = jsonReader.nextString();
                    localizedValues.put(name, value);
                }
                jsonReader.endObject();

                return new LocalizedText(localizedValues);
            } else {
                throw new JsonSyntaxException("Unexpected token " + nextToken);
            }
        }

        @Override
        public void write(JsonWriter jsonWriter, LocalizedText localizedText) throws IOException {
            if (localizedText == null) {
                jsonWriter.nullValue();
            } else if (localizedText.localizedValues != null) {
                jsonWriter.beginObject();
                for (var entry : localizedText.localizedValues.entrySet()) {
                    jsonWriter.name(entry.getKey());
                    jsonWriter.value(entry.getValue());
                }
                jsonWriter.endObject();
            } else {
                jsonWriter.value(localizedText.value);
            }
        }
    }
}
