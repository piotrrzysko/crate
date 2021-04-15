/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
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

package org.elasticsearch.gateway;

import com.carrotsearch.hppc.cursors.ObjectObjectCursor;
import io.crate.common.io.IOUtils;
import org.apache.lucene.util.SetOnce;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.coordination.CoordinationState.PersistedState;
import org.elasticsearch.cluster.coordination.InMemoryPersistedState;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.metadata.MetadataIndexUpgradeService;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugins.MetadataUpgrader;
import org.elasticsearch.transport.TransportService;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Loads (and maybe upgrades) cluster metadata at startup, and persistently stores cluster metadata for future restarts.
 *
 * When started, ensures that this version is compatible with the state stored on disk, and performs a state upgrade if necessary. Note that
 * the state being loaded when constructing the instance of this class is not necessarily the state that will be used as {@link
 * ClusterState#metadata()} because it might be stale or incomplete. Master-eligible nodes must perform an election to find a complete and
 * non-stale state, and master-ineligible nodes receive the real cluster state from the elected master after joining the cluster.
 */
public class GatewayMetaState implements Closeable {

    // Set by calling start()
    private final SetOnce<PersistedState> persistedState = new SetOnce<>();

    public PersistedState getPersistedState() {
        final PersistedState persistedState = this.persistedState.get();
        assert persistedState != null : "not started";
        return persistedState;
    }

    public Metadata getMetadata() {
        return getPersistedState().getLastAcceptedState().metadata();
    }

    public void start(Settings settings, TransportService transportService, ClusterService clusterService,
                      MetadataIndexUpgradeService metadataIndexUpgradeService,
                      MetadataUpgrader metadataUpgrader, LucenePersistedStateFactory lucenePersistedStateFactory) {
        assert persistedState.get() == null : "should only start once, but already have " + persistedState.get();

        if (DiscoveryNode.isMasterNode(settings) || DiscoveryNode.isDataNode(settings)) {
            try {
                persistedState.set(lucenePersistedStateFactory.loadPersistedState((version, metadata) ->
                                                                                      prepareInitialClusterState(transportService, clusterService,
                                                                                                                 ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.get(settings))
                                                                                                                     .version(version)
                                                                                                                     .metadata(upgradeMetadataForNode(metadata, metadataIndexUpgradeService, metadataUpgrader))
                                                                                                                     .build()))
                );
            } catch (IOException e) {
                throw new ElasticsearchException("failed to load metadata", e);
            }
        } else {
            persistedState.set(
                new InMemoryPersistedState(0L, ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.get(settings)).build()));
        }
    }

    // exposed so it can be overridden by tests
    ClusterState prepareInitialClusterState(TransportService transportService, ClusterService clusterService, ClusterState clusterState) {
        assert clusterState.nodes().getLocalNode() == null : "prepareInitialClusterState must only be called once";
        assert transportService.getLocalNode() != null : "transport service is not yet started";
        return Function.<ClusterState>identity()
            .andThen(ClusterStateUpdaters::addStateNotRecoveredBlock)
            .andThen(state -> ClusterStateUpdaters.setLocalNode(state, transportService.getLocalNode()))
            .andThen(state -> ClusterStateUpdaters.upgradeAndArchiveUnknownOrInvalidSettings(state, clusterService.getClusterSettings()))
            .andThen(ClusterStateUpdaters::recoverClusterBlocks)
            .apply(clusterState);
    }

    // exposed so it can be overridden by tests
    Metadata upgradeMetadataForNode(Metadata metadata,
                                    MetadataIndexUpgradeService metadataIndexUpgradeService,
                                    MetadataUpgrader metadataUpgrader) {
        return upgradeMetadata(metadata, metadataIndexUpgradeService, metadataUpgrader);
    }

    /**
     * Elasticsearch 2.0 removed several deprecated features and as well as support for Lucene 3.x. This method calls
     * {@link MetadataIndexUpgradeService} to makes sure that indices are compatible with the current version. The
     * MetadataIndexUpgradeService might also update obsolete settings if needed.
     *
     * @return input <code>metadata</code> if no upgrade is needed or an upgraded metadata
     */
    public static Metadata upgradeMetadata(Metadata metadata,
                                    MetadataIndexUpgradeService metadataIndexUpgradeService,
                                    MetadataUpgrader metadataUpgrader) {
        // upgrade index meta data
        boolean changed = false;
        final Metadata.Builder upgradedMetadata = Metadata.builder(metadata);
        for (IndexMetadata indexMetadata : metadata) {
            IndexMetadata newMetadata = metadataIndexUpgradeService.upgradeIndexMetadata(indexMetadata,
                    Version.CURRENT.minimumIndexCompatibilityVersion());
            changed |= indexMetadata != newMetadata;
            upgradedMetadata.put(newMetadata, false);
        }

        // upgrade global custom meta data
        if (applyPluginUpgraders(metadata.getCustoms(), metadataUpgrader.customMetadataUpgraders,
                                 upgradedMetadata::removeCustom, upgradedMetadata::putCustom)) {
            changed = true;
        }

        // upgrade current templates
        if (applyPluginUpgraders(metadata.getTemplates(), metadataUpgrader.indexTemplateMetadataUpgraders,
                upgradedMetadata::removeTemplate, (s, indexTemplateMetadata) -> upgradedMetadata.put(indexTemplateMetadata))) {
            changed = true;
        }
        return changed ? upgradedMetadata.build() : metadata;
    }

    private static <Data> boolean applyPluginUpgraders(ImmutableOpenMap<String, Data> existingData,
                                                UnaryOperator<Map<String, Data>> upgrader,
                                                Consumer<String> removeData,
                                                BiConsumer<String, Data> putData) {
        // collect current data
        Map<String, Data> existingMap = new HashMap<>();
        for (ObjectObjectCursor<String, Data> customCursor : existingData) {
            existingMap.put(customCursor.key, customCursor.value);
        }
        // upgrade global custom meta data
        Map<String, Data> upgradedCustoms = upgrader.apply(existingMap);
        if (upgradedCustoms.equals(existingMap) == false) {
            // remove all data first so a plugin can remove custom metadata or templates if needed
            existingMap.keySet().forEach(removeData);
            for (Map.Entry<String, Data> upgradedCustomEntry : upgradedCustoms.entrySet()) {
                putData.accept(upgradedCustomEntry.getKey(), upgradedCustomEntry.getValue());
            }
            return true;
        }
        return false;
    }

    @Override
    public void close() throws IOException {
        IOUtils.close(persistedState.get());
    }

}
