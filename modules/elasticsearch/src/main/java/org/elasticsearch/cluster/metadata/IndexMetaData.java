/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this 
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.cluster.metadata;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.JsonToken;
import org.elasticsearch.util.MapBuilder;
import org.elasticsearch.util.Nullable;
import org.elasticsearch.util.Preconditions;
import org.elasticsearch.util.concurrent.Immutable;
import org.elasticsearch.util.io.stream.StreamInput;
import org.elasticsearch.util.io.stream.StreamOutput;
import org.elasticsearch.util.json.JsonBuilder;
import org.elasticsearch.util.json.ToJson;
import org.elasticsearch.util.settings.ImmutableSettings;
import org.elasticsearch.util.settings.Settings;

import java.io.IOException;
import java.util.Map;

import static org.elasticsearch.util.settings.ImmutableSettings.*;

/**
 * @author kimchy (shay.banon)
 */
@Immutable
public class IndexMetaData {

    public static final String SETTING_NUMBER_OF_SHARDS = "index.number_of_shards";

    public static final String SETTING_NUMBER_OF_REPLICAS = "index.number_of_replicas";

    private final String index;

    private final ImmutableSet<String> aliases;

    private final Settings settings;

    private final ImmutableMap<String, String> mappings;

    private transient final int totalNumberOfShards;

    private IndexMetaData(String index, Settings settings, ImmutableMap<String, String> mappings) {
        Preconditions.checkArgument(settings.getAsInt(SETTING_NUMBER_OF_SHARDS, -1) != -1, "must specify numberOfShards for index [" + index + "]");
        Preconditions.checkArgument(settings.getAsInt(SETTING_NUMBER_OF_REPLICAS, -1) != -1, "must specify numberOfReplicas for index [" + index + "]");
        this.index = index;
        this.settings = settings;
        this.mappings = mappings;
        this.totalNumberOfShards = numberOfShards() * (numberOfReplicas() + 1);

        this.aliases = ImmutableSet.of(settings.getAsArray("index.aliases"));
    }

    public String index() {
        return index;
    }

    public int numberOfShards() {
        return settings.getAsInt(SETTING_NUMBER_OF_SHARDS, -1);
    }

    public int numberOfReplicas() {
        return settings.getAsInt(SETTING_NUMBER_OF_REPLICAS, -1);
    }

    public int totalNumberOfShards() {
        return totalNumberOfShards;
    }

    public Settings settings() {
        return settings;
    }

    public ImmutableSet<String> aliases() {
        return this.aliases;
    }

    public ImmutableMap<String, String> mappings() {
        return mappings;
    }

    public String mapping(String mappingType) {
        return mappings.get(mappingType);
    }

    public static Builder newIndexMetaDataBuilder(String index) {
        return new Builder(index);
    }

    public static Builder newIndexMetaDataBuilder(IndexMetaData indexMetaData) {
        return new Builder(indexMetaData);
    }

    public static class Builder {

        private String index;

        private Settings settings = ImmutableSettings.Builder.EMPTY_SETTINGS;

        private MapBuilder<String, String> mappings = MapBuilder.newMapBuilder();

        public Builder(String index) {
            this.index = index;
        }

        public Builder(IndexMetaData indexMetaData) {
            this(indexMetaData.index());
            settings(indexMetaData.settings());
            mappings.putAll(indexMetaData.mappings);
        }

        public String index() {
            return index;
        }

        public Builder numberOfShards(int numberOfShards) {
            settings = settingsBuilder().put(settings).put(SETTING_NUMBER_OF_SHARDS, numberOfShards).build();
            return this;
        }

        public int numberOfShards() {
            return settings.getAsInt(SETTING_NUMBER_OF_SHARDS, -1);
        }

        public Builder numberOfReplicas(int numberOfReplicas) {
            settings = settingsBuilder().put(settings).put(SETTING_NUMBER_OF_REPLICAS, numberOfReplicas).build();
            return this;
        }

        public int numberOfReplicas() {
            return settings.getAsInt(SETTING_NUMBER_OF_REPLICAS, -1);
        }

        public Builder settings(Settings.Builder settings) {
            this.settings = settings.build();
            return this;
        }

        public Builder settings(Settings settings) {
            this.settings = settings;
            return this;
        }

        public Builder removeMapping(String mappingType) {
            mappings.remove(mappingType);
            return this;
        }

        public Builder putMapping(String mappingType, String mappingSource) {
            mappings.put(mappingType, mappingSource);
            return this;
        }

        public IndexMetaData build() {
            return new IndexMetaData(index, settings, mappings.immutableMap());
        }

        public static void toJson(IndexMetaData indexMetaData, JsonBuilder builder, ToJson.Params params) throws IOException {
            builder.startObject(indexMetaData.index());

            builder.startObject("settings");
            for (Map.Entry<String, String> entry : indexMetaData.settings().getAsMap().entrySet()) {
                builder.field(entry.getKey(), entry.getValue());
            }
            builder.endObject();

            builder.startObject("mappings");
            for (Map.Entry<String, String> entry : indexMetaData.mappings().entrySet()) {
                builder.startObject(entry.getKey());
                builder.field("source", entry.getValue());
                builder.endObject();
            }
            builder.endObject();

            builder.endObject();
        }

        public static IndexMetaData fromJson(JsonParser jp, @Nullable Settings globalSettings) throws IOException {
            Builder builder = new Builder(jp.getCurrentName());

            String currentFieldName = null;
            JsonToken token = jp.nextToken();
            while ((token = jp.nextToken()) != JsonToken.END_OBJECT) {
                if (token == JsonToken.FIELD_NAME) {
                    currentFieldName = jp.getCurrentName();
                } else if (token == JsonToken.START_OBJECT) {
                    if ("settings".equals(currentFieldName)) {
                        ImmutableSettings.Builder settingsBuilder = settingsBuilder().globalSettings(globalSettings);
                        while ((token = jp.nextToken()) != JsonToken.END_OBJECT) {
                            String key = jp.getCurrentName();
                            token = jp.nextToken();
                            String value = jp.getText();
                            settingsBuilder.put(key, value);
                        }
                        builder.settings(settingsBuilder.build());
                    } else if ("mappings".equals(currentFieldName)) {
                        while ((token = jp.nextToken()) != JsonToken.END_OBJECT) {
                            String mappingType = jp.getCurrentName();
                            String mappingSource = null;
                            while ((token = jp.nextToken()) != JsonToken.END_OBJECT) {
                                if (token == JsonToken.FIELD_NAME) {
                                    if ("source".equals(jp.getCurrentName())) {
                                        jp.nextToken();
                                        mappingSource = jp.getText();
                                    }
                                }
                            }
                            if (mappingSource == null) {
                                // crap, no mapping source, warn?
                            } else {
                                builder.putMapping(mappingType, mappingSource);
                            }
                        }
                    }
                }
            }
            return builder.build();
        }

        public static IndexMetaData readFrom(StreamInput in, Settings globalSettings) throws IOException {
            Builder builder = new Builder(in.readUTF());
            builder.settings(readSettingsFromStream(in, globalSettings));
            int mappingsSize = in.readVInt();
            for (int i = 0; i < mappingsSize; i++) {
                builder.putMapping(in.readUTF(), in.readUTF());
            }
            return builder.build();
        }

        public static void writeTo(IndexMetaData indexMetaData, StreamOutput out) throws IOException {
            out.writeUTF(indexMetaData.index());
            writeSettingsToStream(indexMetaData.settings(), out);
            out.writeVInt(indexMetaData.mappings().size());
            for (Map.Entry<String, String> entry : indexMetaData.mappings().entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeUTF(entry.getValue());
            }
        }
    }
}
