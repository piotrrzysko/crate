/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.node;

import io.crate.common.io.IOUtils;
import io.crate.integrationtests.SQLTransportIntegrationTest;
import io.crate.testing.UseJdbc;
import joptsimple.OptionSet;
import org.apache.lucene.util.LuceneTestCase;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.cli.MockTerminal;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.coordination.DetachClusterCommand;
import org.elasticsearch.cluster.coordination.ElasticsearchNodeCommand;
import org.elasticsearch.cluster.coordination.NoMasterBlockService;
import org.elasticsearch.cluster.coordination.UnsafeBootstrapMasterCommand;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.env.NodeMetadata;
import org.elasticsearch.env.TestEnvironment;
import org.elasticsearch.gateway.PersistedClusterStateService;
import org.elasticsearch.node.Node;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.InternalTestCluster;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.elasticsearch.indices.recovery.RecoverySettings.INDICES_RECOVERY_MAX_BYTES_PER_SEC_SETTING;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

@ESIntegTestCase.ClusterScope(scope = ESIntegTestCase.Scope.TEST, numDataNodes = 0, autoManageMasterNodes = false)
public class UnsafeBootstrapAndDetachCommandIT extends SQLTransportIntegrationTest {

    private MockTerminal executeCommand(ElasticsearchNodeCommand command, Environment environment, boolean abort)
        throws Exception {
        final MockTerminal terminal = new MockTerminal();
        final OptionSet options = command.getParser().parse();
        final String input;

        if (abort) {
            input = randomValueOtherThanMany(c -> c.equalsIgnoreCase("y"), () -> randomAlphaOfLength(1));
        } else {
            input = randomBoolean() ? "y" : "Y";
        }

        terminal.addTextInput(input);

        try {
            command.execute(terminal, options, environment);
        } finally {
            assertThat(terminal.getOutput(), containsString(ElasticsearchNodeCommand.STOP_WARNING_MSG));
        }

        return terminal;
    }

    private MockTerminal unsafeBootstrap(Environment environment, boolean abort) throws Exception {
        final MockTerminal terminal = executeCommand(new UnsafeBootstrapMasterCommand(), environment, abort);
        assertThat(terminal.getOutput(), containsString(UnsafeBootstrapMasterCommand.CONFIRMATION_MSG));
        assertThat(terminal.getOutput(), containsString(UnsafeBootstrapMasterCommand.MASTER_NODE_BOOTSTRAPPED_MSG));
        return terminal;
    }

    private MockTerminal detachCluster(Environment environment, boolean abort) throws Exception {
        final MockTerminal terminal = executeCommand(new DetachClusterCommand(), environment, abort);
        assertThat(terminal.getOutput(), containsString(DetachClusterCommand.CONFIRMATION_MSG));
        assertThat(terminal.getOutput(), containsString(DetachClusterCommand.NODE_DETACHED_MSG));
        return terminal;
    }

    public MockTerminal unsafeBootstrap(Environment environment) throws Exception {
        return unsafeBootstrap(environment, false);
    }

    private MockTerminal detachCluster(Environment environment) throws Exception {
        return detachCluster(environment, false);
    }

    private void expectThrows(LuceneTestCase.ThrowingRunnable runnable, String message) {
        ElasticsearchException ex = expectThrows(ElasticsearchException.class, runnable);
        assertThat(ex.getMessage(), containsString(message));
    }

    public void testBootstrapNotMasterEligible() {
        final Environment environment = TestEnvironment.newEnvironment(Settings.builder()
                                                                           .put(internalCluster().getDefaultSettings())
                                                                           .put(Node.NODE_MASTER_SETTING.getKey(), false)
                                                                           .build());
        expectThrows(() -> unsafeBootstrap(environment), UnsafeBootstrapMasterCommand.NOT_MASTER_NODE_MSG);
    }

    public void testBootstrapNoDataFolder() {
        final Environment environment = TestEnvironment.newEnvironment(internalCluster().getDefaultSettings());
        expectThrows(() -> unsafeBootstrap(environment), ElasticsearchNodeCommand.NO_NODE_FOLDER_FOUND_MSG);
    }

    public void testDetachNoDataFolder() {
        final Environment environment = TestEnvironment.newEnvironment(internalCluster().getDefaultSettings());
        expectThrows(() -> detachCluster(environment), ElasticsearchNodeCommand.NO_NODE_FOLDER_FOUND_MSG);
    }

    public void testBootstrapNodeLocked() throws IOException {
        Settings envSettings = buildEnvSettings(Settings.EMPTY);
        Environment environment = TestEnvironment.newEnvironment(envSettings);
        try (NodeEnvironment ignored = new NodeEnvironment(envSettings, environment)) {
            expectThrows(() -> unsafeBootstrap(environment), ElasticsearchNodeCommand.FAILED_TO_OBTAIN_NODE_LOCK_MSG);
        }
    }

    public void testDetachNodeLocked() throws IOException {
        Settings envSettings = buildEnvSettings(Settings.EMPTY);
        Environment environment = TestEnvironment.newEnvironment(envSettings);
        try (NodeEnvironment ignored = new NodeEnvironment(envSettings, environment)) {
            expectThrows(() -> detachCluster(environment), ElasticsearchNodeCommand.FAILED_TO_OBTAIN_NODE_LOCK_MSG);
        }
    }

    public void testBootstrapNoNodeMetadata() throws IOException {
        Settings envSettings = buildEnvSettings(Settings.EMPTY);
        Environment environment = TestEnvironment.newEnvironment(envSettings);
        try (NodeEnvironment nodeEnvironment = new NodeEnvironment(envSettings, environment)) {
            NodeMetadata.FORMAT.cleanupOldFiles(-1, nodeEnvironment.nodeDataPaths());
        }

        expectThrows(() -> unsafeBootstrap(environment), ElasticsearchNodeCommand.NO_NODE_METADATA_FOUND_MSG);
    }

    public void testBootstrapNotBootstrappedCluster() throws Exception {
        String node = internalCluster().startNode(
            Settings.builder()
                .put(Node.INITIAL_STATE_TIMEOUT_SETTING.getKey(), "0s") // to ensure quick node startup
                .build());
        assertBusy(() -> {
            ClusterState state = client().admin().cluster().prepareState().setLocal(true)
                .execute().actionGet().getState();
            assertTrue(state.blocks().hasGlobalBlockWithId(NoMasterBlockService.NO_MASTER_BLOCK_ID));
        });

        Settings dataPathSettings = internalCluster().dataPathSettings(node);

        internalCluster().stopRandomDataNode();

        Environment environment = TestEnvironment.newEnvironment(
            Settings.builder().put(internalCluster().getDefaultSettings()).put(dataPathSettings).build());
        expectThrows(() -> unsafeBootstrap(environment), UnsafeBootstrapMasterCommand.EMPTY_LAST_COMMITTED_VOTING_CONFIG_MSG);
    }

    public void testBootstrapNoClusterState() throws IOException {
        internalCluster().setBootstrapMasterNodeIndex(0);
        String node = internalCluster().startNode();
        Settings dataPathSettings = internalCluster().dataPathSettings(node);
        ensureStableCluster(1);
        NodeEnvironment nodeEnvironment = internalCluster().getMasterNodeInstance(NodeEnvironment.class);
        internalCluster().stopRandomDataNode();
        Environment environment = TestEnvironment.newEnvironment(
            Settings.builder().put(internalCluster().getDefaultSettings()).put(dataPathSettings).build());
        IOUtils.rm(Stream.of(nodeEnvironment.nodeDataPaths())
                       .map(path -> path.resolve(PersistedClusterStateService.METADATA_DIRECTORY_NAME))
                       .toArray(Path[]::new));

        expectThrows(() -> unsafeBootstrap(environment), ElasticsearchNodeCommand.CS_MISSING_MSG);
    }

    public void testDetachNoClusterState() throws IOException {
        internalCluster().setBootstrapMasterNodeIndex(0);
        String node = internalCluster().startNode();
        Settings dataPathSettings = internalCluster().dataPathSettings(node);
        ensureStableCluster(1);
        NodeEnvironment nodeEnvironment = internalCluster().getMasterNodeInstance(NodeEnvironment.class);
        internalCluster().stopRandomDataNode();
        Environment environment = TestEnvironment.newEnvironment(
            Settings.builder().put(internalCluster().getDefaultSettings()).put(dataPathSettings).build());
        IOUtils.rm(Stream.of(nodeEnvironment.nodeDataPaths())
                       .map(path -> path.resolve(PersistedClusterStateService.METADATA_DIRECTORY_NAME))
                       .toArray(Path[]::new));

        expectThrows(() -> detachCluster(environment), ElasticsearchNodeCommand.CS_MISSING_MSG);
    }

    public void testBootstrapAbortedByUser() throws IOException {
        internalCluster().setBootstrapMasterNodeIndex(0);
        String node = internalCluster().startNode();
        Settings dataPathSettings = internalCluster().dataPathSettings(node);
        ensureStableCluster(1);
        internalCluster().stopRandomDataNode();

        Environment environment = TestEnvironment.newEnvironment(
            Settings.builder().put(internalCluster().getDefaultSettings()).put(dataPathSettings).build());
        expectThrows(() -> unsafeBootstrap(environment, true), ElasticsearchNodeCommand.ABORTED_BY_USER_MSG);
    }

    public void testDetachAbortedByUser() throws IOException {
        internalCluster().setBootstrapMasterNodeIndex(0);
        String node = internalCluster().startNode();
        Settings dataPathSettings = internalCluster().dataPathSettings(node);
        ensureStableCluster(1);
        internalCluster().stopRandomDataNode();

        Environment environment = TestEnvironment.newEnvironment(
            Settings.builder().put(internalCluster().getDefaultSettings()).put(dataPathSettings).build());
        expectThrows(() -> detachCluster(environment, true), ElasticsearchNodeCommand.ABORTED_BY_USER_MSG);
    }

    @Test
    @UseJdbc(0)
    public void test3MasterNodes2Failed() throws Exception {
        internalCluster().setBootstrapMasterNodeIndex(2);
        List<String> masterNodes = new ArrayList<>();

        logger.info("--> start 1st master-eligible node");
        masterNodes.add(internalCluster().startMasterOnlyNode(Settings.builder()
                .put(Node.INITIAL_STATE_TIMEOUT_SETTING.getKey(), "0s")
                .build())); // node ordinal 0

        logger.info("--> start one data-only node");
        String dataNode = internalCluster().startDataOnlyNode(Settings.builder()
                .put(Node.INITIAL_STATE_TIMEOUT_SETTING.getKey(), "0s")
                .build()); // node ordinal 1

        logger.info("--> start 2nd and 3rd master-eligible nodes and bootstrap");
        masterNodes.addAll(internalCluster().startMasterOnlyNodes(2)); // node ordinals 2 and 3

        logger.info("--> wait for all nodes to join the cluster");
        ensureStableCluster(4);

        logger.info("--> create index test");
        execute("create table doc.test (x int)");
        ensureGreen("test");

        Settings master1DataPathSettings = internalCluster().dataPathSettings(masterNodes.get(0));
        Settings master2DataPathSettings = internalCluster().dataPathSettings(masterNodes.get(1));
        Settings master3DataPathSettings = internalCluster().dataPathSettings(masterNodes.get(2));
        Settings dataNodeDataPathSettings = internalCluster().dataPathSettings(dataNode);

        logger.info("--> stop 2nd and 3d master eligible node");
        internalCluster().stopRandomNode(InternalTestCluster.nameFilter(masterNodes.get(1)));
        internalCluster().stopRandomNode(InternalTestCluster.nameFilter(masterNodes.get(2)));

        logger.info("--> ensure NO_MASTER_BLOCK on data-only node");
        assertBusy(() -> {
            ClusterState state = internalCluster().client(dataNode).admin().cluster().prepareState().setLocal(true)
                    .execute().actionGet().getState();
            assertTrue(state.blocks().hasGlobalBlockWithId(NoMasterBlockService.NO_MASTER_BLOCK_ID));
        });

        logger.info("--> try to unsafely bootstrap 1st master-eligible node, while node lock is held");
        Environment environmentMaster1 = TestEnvironment.newEnvironment(
            Settings.builder().put(internalCluster().getDefaultSettings()).put(master1DataPathSettings).build());
        expectThrows(() -> unsafeBootstrap(environmentMaster1), UnsafeBootstrapMasterCommand.FAILED_TO_OBTAIN_NODE_LOCK_MSG);

        logger.info("--> stop 1st master-eligible node and data-only node");
        NodeEnvironment nodeEnvironment = internalCluster().getMasterNodeInstance(NodeEnvironment.class);
        internalCluster().stopRandomNode(InternalTestCluster.nameFilter(masterNodes.get(0)));
        internalCluster().stopRandomDataNode();

        logger.info("--> unsafely-bootstrap 1st master-eligible node");
        MockTerminal terminal = unsafeBootstrap(environmentMaster1);
        Metadata metadata = ElasticsearchNodeCommand.createPersistedClusterStateService(nodeEnvironment.nodeDataPaths())
            .loadBestOnDiskState().metadata;
        assertThat(terminal.getOutput(), containsString(
            String.format(Locale.ROOT, UnsafeBootstrapMasterCommand.CLUSTER_STATE_TERM_VERSION_MSG_FORMAT,
                          metadata.coordinationMetadata().term(), metadata.version())));

        logger.info("--> start 1st master-eligible node");
        internalCluster().startMasterOnlyNode(master1DataPathSettings);

        logger.info("--> detach-cluster on data-only node");
        Environment environmentData = TestEnvironment.newEnvironment(
            Settings.builder().put(internalCluster().getDefaultSettings()).put(dataNodeDataPathSettings).build());
        detachCluster(environmentData, false);

        logger.info("--> start data-only node");
        String dataNode2 = internalCluster().startDataOnlyNode(dataNodeDataPathSettings);

        logger.info("--> ensure there is no NO_MASTER_BLOCK and unsafe-bootstrap is reflected in cluster state");
        assertBusy(() -> {
            ClusterState state = internalCluster().client(dataNode2).admin().cluster().prepareState().setLocal(true)
                    .execute().actionGet().getState();
            assertFalse(state.blocks().hasGlobalBlockWithId(NoMasterBlockService.NO_MASTER_BLOCK_ID));
            assertTrue(state.metadata().persistentSettings().getAsBoolean(UnsafeBootstrapMasterCommand.UNSAFE_BOOTSTRAP.getKey(), false));
        });

        logger.info("--> ensure index test is green");
        ensureGreen("test");

        logger.info("--> detach-cluster on 2nd and 3rd master-eligible nodes");
        Environment environmentMaster2 = TestEnvironment.newEnvironment(
            Settings.builder().put(internalCluster().getDefaultSettings()).put(master2DataPathSettings).build());
        detachCluster(environmentMaster2, false);
        Environment environmentMaster3 = TestEnvironment.newEnvironment(
            Settings.builder().put(internalCluster().getDefaultSettings()).put(master3DataPathSettings).build());
        detachCluster(environmentMaster3, false);

        logger.info("--> start 2nd and 3rd master-eligible nodes and ensure 4 nodes stable cluster");
        internalCluster().startMasterOnlyNode(master2DataPathSettings);
        internalCluster().startMasterOnlyNode(master3DataPathSettings);
        ensureStableCluster(4);
    }

    public void testAllMasterEligibleNodesFailedDanglingIndexImport() throws Exception {
        internalCluster().setBootstrapMasterNodeIndex(0);

        logger.info("--> start mixed data and master-eligible node and bootstrap cluster");
        String masterNode = internalCluster().startNode(); // node ordinal 0

        logger.info("--> start data-only node and ensure 2 nodes stable cluster");
        String dataNode = internalCluster().startDataOnlyNode(); // node ordinal 1
        ensureStableCluster(2);

        execute("create table doc.test(x int)");

        logger.info("--> index 1 doc and ensure index is green");

        execute("insert into doc.test values(1)");
        ensureGreen("test");

        logger.info("--> verify 1 doc in the index");

        execute("select count(*) from doc.test");
        assertThat(response.rows()[0][0], is(1L));

        logger.info("--> stop data-only node and detach it from the old cluster");
        Settings dataNodeDataPathSettings = internalCluster().dataPathSettings(dataNode);
        internalCluster().stopRandomNode(InternalTestCluster.nameFilter(dataNode));
        final Environment environment = TestEnvironment.newEnvironment(
            Settings.builder().put(internalCluster().getDefaultSettings()).put(dataNodeDataPathSettings).build());
        detachCluster(environment, false);

        logger.info("--> stop master-eligible node, clear its data and start it again - new cluster should form");
        internalCluster().restartNode(masterNode, new InternalTestCluster.RestartCallback(){
            @Override
            public boolean clearData(String nodeName) {
                return true;
            }
        });

        logger.info("--> start data-only only node and ensure 2 nodes stable cluster");
        internalCluster().startDataOnlyNode(dataNodeDataPathSettings);
        ensureStableCluster(2);

        // TODO: @AwaitsFix(bugUrl = "https://github.com/elastic/elasticsearch/issues/48701") // dangling indices
//        logger.info("--> verify that the dangling index exists and has green status");
//        assertBusy(() -> {
//            assertThat(indexExists("test"), equalTo(true));
//        });
//        ensureGreen("test");
//
//        logger.info("--> verify the doc is there");
//        assertThat(client().prepareGet("test", "1").execute().actionGet().isExists(), equalTo(true));
    }

    public void testNoInitialBootstrapAfterDetach() throws Exception {
        internalCluster().setBootstrapMasterNodeIndex(0);
        String masterNode = internalCluster().startMasterOnlyNode();
        Settings masterNodeDataPathSettings = internalCluster().dataPathSettings(masterNode);
        internalCluster().stopCurrentMasterNode();

        final Environment environment = TestEnvironment.newEnvironment(
            Settings.builder().put(internalCluster().getDefaultSettings()).put(masterNodeDataPathSettings).build());
        detachCluster(environment);

        String node = internalCluster().startMasterOnlyNode(Settings.builder()
                                                                // give the cluster 2 seconds to elect the master (it should not)
                                                                .put(Node.INITIAL_STATE_TIMEOUT_SETTING.getKey(), "2s")
                                                                .put(masterNodeDataPathSettings)
                                                                .build());

        ClusterState state = internalCluster().client().admin().cluster().prepareState().setLocal(true)
            .execute().actionGet().getState();
        assertTrue(state.blocks().hasGlobalBlockWithId(NoMasterBlockService.NO_MASTER_BLOCK_ID));

        internalCluster().stopRandomNode(InternalTestCluster.nameFilter(node));
    }

    @Test
    public void testCanRunUnsafeBootstrapAfterErroneousDetachWithoutLoosingMetaData() throws Exception {
        internalCluster().setBootstrapMasterNodeIndex(0);
        String masterNode = internalCluster().startMasterOnlyNode();
        Settings masterNodeDataPathSettings = internalCluster().dataPathSettings(masterNode);

        execute("SET GLOBAL PERSISTENT indices.recovery.max_bytes_per_sec = '1234kb'");

        ClusterState state = internalCluster().client().admin().cluster().prepareState().execute().actionGet().getState();
        assertThat(state.metadata().persistentSettings().get(INDICES_RECOVERY_MAX_BYTES_PER_SEC_SETTING.getKey()),
                   equalTo("1234kb"));

        internalCluster().stopCurrentMasterNode();

        final Environment environment = TestEnvironment.newEnvironment(
            Settings.builder().put(internalCluster().getDefaultSettings()).put(masterNodeDataPathSettings).build());
        detachCluster(environment);
        unsafeBootstrap(environment);

        internalCluster().startMasterOnlyNode(masterNodeDataPathSettings);
        ensureGreen();

        state = internalCluster().client().admin().cluster().prepareState().execute().actionGet().getState();
        assertThat(state.metadata().settings().get(INDICES_RECOVERY_MAX_BYTES_PER_SEC_SETTING.getKey()),
                   equalTo("1234kb"));
    }
}
