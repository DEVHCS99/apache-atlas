/*
 * Copyright 2023 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.couchbase.atlas.connector;

import com.couchbase.atlas.connector.entities.CouchbaseBucket;
import com.couchbase.atlas.connector.entities.CouchbaseCluster;
import com.couchbase.atlas.connector.entities.CouchbaseCollection;
import com.couchbase.atlas.connector.entities.CouchbaseField;
import com.couchbase.atlas.connector.entities.CouchbaseFieldType;
import com.couchbase.atlas.connector.entities.CouchbaseScope;
import com.couchbase.client.core.deps.io.netty.buffer.ByteBuf;
import com.couchbase.client.dcp.Client;
import com.couchbase.client.dcp.ControlEventHandler;
import com.couchbase.client.dcp.DataEventHandler;
import com.couchbase.client.dcp.StreamFrom;
import com.couchbase.client.dcp.StreamTo;
import com.couchbase.client.dcp.highlevel.internal.CollectionIdAndKey;
import com.couchbase.client.dcp.highlevel.internal.CollectionsManifest;
import com.couchbase.client.dcp.message.DcpMutationMessage;
import com.couchbase.client.dcp.message.MessageUtil;
import com.couchbase.client.dcp.transport.netty.ChannelFlowController;
import com.couchbase.client.java.json.JsonObject;
import org.apache.atlas.AtlasClientV2;
import org.apache.atlas.hook.AtlasHook;
import org.apache.atlas.model.instance.AtlasEntity;
import org.apache.atlas.model.notification.HookNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CouchbaseHook extends AtlasHook implements ControlEventHandler, DataEventHandler {
    private static final Logger LOG = LoggerFactory.getLogger(CouchbaseHook.class);
    protected static CouchbaseHook INSTANCE;
    protected static Client DCP;
    protected static AtlasClientV2 ATLAS;
    private CouchbaseCluster clusterEntity;
    private CouchbaseBucket bucketEntity;

    private static Consumer<List<AtlasEntity>> createInterceptor;
    private static Consumer<List<AtlasEntity>> updateInterceptor;

    private static boolean loop = true;

    /**
     * START HERE
     *
     * @param args
     */
    public static void main(String[] args) {
        // create instances of DCP client,
        DCP = CBConfig.dcpClient();
        // Atlas client,
        ATLAS = AtlasConfig.client();
        // and DCP handler
        INSTANCE = new CouchbaseHook();
        // register DCP handler with DCP client
        DCP.controlEventHandler(INSTANCE);
        DCP.dataEventHandler(INSTANCE);

        // Connect to the cluster
        DCP.connect().block();
        LOG.info("DCP client connected.");
        // Ensure the existence of corresponding
        // CouchbaseCluster, CouchbaseBucket, CouchbaseScope
        // entities and store them in local cache
        INSTANCE.initializeAtlasContext();
        // Start listening to DCP
        DCP.initializeState(StreamFrom.NOW, StreamTo.INFINITY).block();
        System.out.println("Starting the stream...");
        DCP.startStreaming().block();
        System.out.println("Started the stream.");
        // And then just loop the loop
        try {
            while (loop) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {

        } finally {
            DCP.disconnect().block();
        }
    }

    /**
     * Ensures the existence of CouchbaseCluster,
     * CouchbaseBucket and Couchbase scope entities
     * and stores them into local cache
     */
    private void initializeAtlasContext() {
        LOG.debug("Creating cluster/bucket/scope entities");

        clusterEntity = new CouchbaseCluster()
                .name(CBConfig.address())
                .url(CBConfig.address())
                .get();

        bucketEntity = new CouchbaseBucket()
                .name(CBConfig.bucket())
                .cluster(clusterEntity)
                .get();

        List<AtlasEntity> entitiesToCreate = new ArrayList<>();
        if (!clusterEntity.exists(ATLAS)) {
            entitiesToCreate.add(clusterEntity.atlasEntity(ATLAS));
        }

        if (!bucketEntity.exists(ATLAS)) {
            entitiesToCreate.add(bucketEntity.atlasEntity(ATLAS));
        }

        if (!entitiesToCreate.isEmpty()) {
            createEntities(entitiesToCreate);
        }
    }

    private void createEntities(List<AtlasEntity> entities) {
        if (createInterceptor != null) {
            createInterceptor.accept(entities);
            return;
        }
        AtlasEntity.AtlasEntitiesWithExtInfo info = new AtlasEntity.AtlasEntitiesWithExtInfo(entities);
        HookNotification.EntityCreateRequestV2 requestV2 = new HookNotification.EntityCreateRequestV2("couchbase", info);
        notifyEntities(Arrays.asList(requestV2), null);
    }

    private void updateEntities(List<AtlasEntity> entities) {
        if (updateInterceptor != null) {
            updateInterceptor.accept(entities);
            return;
        }
        AtlasEntity.AtlasEntitiesWithExtInfo info = new AtlasEntity.AtlasEntitiesWithExtInfo(entities);
        HookNotification.EntityUpdateRequestV2 requestV2 = new HookNotification.EntityUpdateRequestV2("couchbase", info);
        notifyEntities(Arrays.asList(requestV2), null);
    }

    @Override
    public void onEvent(ChannelFlowController flowController, ByteBuf event) {
        // Probabilistic sampling
        if (Math.random() > CBConfig.dcpSampleRatio()) {
            LOG.debug("Skipping DCP message.");
            return;
        }
        if (DcpMutationMessage.is(event)) {
            try {
                // Borrowed from Couchbeans :)
                // Gathering some information about the message.
                CollectionIdAndKey ckey = MessageUtil.getCollectionIdAndKey(event, true);
                CollectionsManifest.CollectionInfo collectionInfo = collectionInfo(MessageUtil.getVbucket(event), ckey.collectionId());
                String collectionName = collectionInfo.name();
                String scopeName = collectionInfo.scope().name();

                LOG.debug("Received DCP mutation message for scope '{}' and collection '{}'", scopeName, collectionName);

                CouchbaseScope scopeEntity = bucketEntity.scope(scopeName);
                // Because Atlas doesn't support upserts,
                // we need to send new entites in a separate message
                // from already existing ones
                List<AtlasEntity> toCreate = new ArrayList<>();
                List<AtlasEntity> toUpdate = new ArrayList<>();
                if (!scopeEntity.exists(ATLAS)) {
                    toCreate.add(scopeEntity.atlasEntity(ATLAS));
                    LOG.debug("Creating scope: {}", scopeEntity.qualifiedName());
                } else {
                    toUpdate.add(scopeEntity.atlasEntity(ATLAS));
                    LOG.debug("Updating scope: {}", scopeEntity.qualifiedName());
                }
                CouchbaseCollection collectionEntity = scopeEntity.collection(collectionName);
                // Let's record this attempt to analyze a collection document
                // so that we can calculate field frequencies
                // when filtering them via DCP_FIELD_THRESHOLD
                collectionEntity.incrementAnalyzedDocuments();
                // and then schedule it to be sent to Atlas
                if (!collectionEntity.exists(ATLAS)) {
                    toCreate.add(collectionEntity.atlasEntity(ATLAS));
                } else {
                    toUpdate.add(collectionEntity.atlasEntity(ATLAS));
                }

                Map<String, Object> document = JsonObject.fromJson(DcpMutationMessage.contentBytes(event)).toMap();

                System.out.println(String.format("Document keys: %s", document.keySet()));
                // for each field in the document...
                document.entrySet().stream()
                        // transform the field into CouchbaseField either by loading corresponding entity or by creating it
                        .filter(e -> e.getValue() != null)
                        .flatMap(entry -> processField(collectionEntity, (Collection<String>) Collections.EMPTY_LIST, null, entry.getKey(), entry.getValue()))
                        // update document counter on the field entity
                        .peek(CouchbaseField::incrementDocumentCount)
                        // Only passes fields that either already in Atlas or pass DCP_FIELD_THRESHOLD setting
                        .filter(field -> field.exists(ATLAS) || field.documentCount() / (float) collectionEntity.documentsAnalyzed() > CBConfig.dcpFieldThreshold())
                        // Schedule the entity either for creation or to be updated in Atlas
                        .forEach(field -> {
                            if (field.exists(ATLAS)) {
                                toUpdate.add(field.atlasEntity(ATLAS));
                            } else {
                                toCreate.add(field.atlasEntity(ATLAS));
                            }
                        });

                createEntities(toCreate);
                updateEntities(toUpdate);

                System.out.println("Notified Atlas");
            } catch (Exception e) {
                LOG.error("Failed to process DCP message", e);
            }
        }
    }

    /**
     * Constructs a {@link CouchbaseField} from field information
     *
     * @param collectionEntity the {@link CouchbaseCollection} to which the field belongs
     * @param path             the path to the field inside the collection document excluding the field itself
     * @param parent           the parent field, if present or null
     * @param name             the name of the field
     * @param value            the value for the field from received document
     * @return constructed or loaded from Atlas {@link CouchbaseField}
     */
    private static Stream<CouchbaseField> processField(CouchbaseCollection collectionEntity, Collection<String> path, @Nullable CouchbaseField parent, String name, Object value) {
        // Let's figure out what type does this field have
        CouchbaseFieldType fieldType = CouchbaseFieldType.infer(value);
        // The full field path as it will be stored in Atlas
        final Collection<String> fieldPath = new ArrayList<>(path);
        fieldPath.add(name);
        // constructing the field entity and loading it from cache or Atlas, if previously stored there
        CouchbaseField rootField = new CouchbaseField()
                .name(name)
                .fieldPath(fieldPath.stream().collect(Collectors.joining(".")))
                .fieldType(fieldType)
                .collection(collectionEntity)
                .parentField(parent)
                .get();
        // return value
        Stream<CouchbaseField> result = Stream.of(rootField);
        // Recursive transformation of embedded object fields
        if (fieldType == CouchbaseFieldType.OBJECT) {
            // Normalizing the value
            if (value instanceof JsonObject) {
                value = ((JsonObject) value).toMap();
            }
            if (value instanceof Map) {
                // Append embedded field entities to the resulting Stream
                result = Stream.concat(
                        result,
                        ((Map<String, ?>) value).entrySet().stream()
                                // recursion
                                .flatMap(entity -> processField(collectionEntity, fieldPath, rootField, entity.getKey(), entity.getValue()))
                );
            } else {
                throw new IllegalArgumentException(String.format("Incorrect value type '%s' for field type 'object': a Map was expected instead.", value.getClass()));
            }
        }

        return result;
    }

    @Override
    public String getMessageSource() {
        return "couchbase";
    }

    /**
     * Looks up the collection name by its vbucket identifier
     *
     * @param vbucket
     * @param collid
     * @return the name of the collection
     */
    private static CollectionsManifest.CollectionInfo collectionInfo(int vbucket, long collid) {
        return DCP.sessionState()
                .get(vbucket)
                .getCollectionsManifest()
                .getCollection(collid);
    }

    protected static void setEntityInterceptors(Consumer<List<AtlasEntity>> createInterceptor, Consumer<List<AtlasEntity>> updateInterceptor) {
        CouchbaseHook.createInterceptor = createInterceptor;
        CouchbaseHook.updateInterceptor = updateInterceptor;
    }

    static void loop(boolean loop) {
        CouchbaseHook.loop = loop;
    }
}
