/*
 * Copyright (c) 2017-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.graphalgo.core.loading;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.neo4j.graphalgo.BaseTest;
import org.neo4j.graphalgo.NodeLabel;
import org.neo4j.graphalgo.NodeProjection;
import org.neo4j.graphalgo.Orientation;
import org.neo4j.graphalgo.PropertyMapping;
import org.neo4j.graphalgo.PropertyMappings;
import org.neo4j.graphalgo.RelationshipProjection;
import org.neo4j.graphalgo.RelationshipType;
import org.neo4j.graphalgo.StoreLoaderBuilder;
import org.neo4j.graphalgo.api.Graph;
import org.neo4j.graphalgo.core.Aggregation;
import org.neo4j.graphalgo.core.GraphLoader;
import org.neo4j.graphalgo.core.huge.AdjacencyList;
import org.neo4j.graphalgo.core.huge.AdjacencyOffsets;
import org.neo4j.graphalgo.core.huge.HugeGraph;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.neo4j.graphalgo.NodeLabel.ALL_NODES;
import static org.neo4j.graphalgo.TestGraph.Builder.fromGdl;
import static org.neo4j.graphalgo.TestSupport.assertGraphEquals;
import static org.neo4j.graphalgo.TestSupport.mapEquals;
import static org.neo4j.graphalgo.compat.MapUtil.map;
import static org.neo4j.values.storable.NumberType.FLOATING_POINT;

class GraphStoreTest extends BaseTest {

    private static final NodeLabel LABEL_A = NodeLabel.of("A");

    @BeforeEach
    void setup() {
        runQuery(" CREATE (a:A {nodeProperty: 33, a: 33})" +
                 ", (b:B {nodeProperty: 42, b: 42})" +
                 ", (c:Ignore)" +
                 ", (a)-[:T1 {property1: 42, property2: 1337}]->(b)" +
                 ", (a)-[:T2 {property1: 43}]->(b)" +
                 ", (a)-[:T3 {property2: 1338}]->(b)" +
                 ", (a)-[:T1 {property1: 33}]->(c)" +
                 ", (c)-[:T1 {property1: 33}]->(a)" +
                 ", (b)-[:T1 {property1: 33}]->(c)" +
                 ", (c)-[:T1 {property1: 33}]->(b)");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validRelationshipFilterParameters")
    void testFilteringGraphsByRelationships(
        String desc,
        List<RelationshipType> relTypes,
        Optional<String> relProperty,
        String expectedGraph
    ) {
        GraphLoader graphLoader = new StoreLoaderBuilder()
            .api(db)
            .graphName("myGraph")
            .addNodeProjection(NodeProjection.of("A"))
            .addNodeProjection(NodeProjection.of("B"))
            .relationshipProjections(relationshipProjections())
            .build();

        GraphStore graphStore = graphLoader.graphStore(NativeFactory.class);

        Graph filteredGraph = graphStore.getGraph(relTypes, relProperty);

        assertGraphEquals(fromGdl(expectedGraph), filteredGraph);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validNodeFilterParameters")
    void testFilteringGraphsByNodeLabels(String desc, List<NodeLabel> labels, String expectedGraph) {
        GraphLoader graphLoader = new StoreLoaderBuilder()
            .api(db)
            .graphName("myGraph")
            .nodeProjections(nodeProjections())
            .addRelationshipProjection(RelationshipProjection.of("T1", Orientation.NATURAL))
            .build();

        GraphStore graphStore = graphLoader.graphStore(NativeFactory.class);

        Graph filteredGraph = graphStore.getGraph(labels, graphStore.relationshipTypes(), Optional.empty(), 1);

        assertGraphEquals(fromGdl(expectedGraph), filteredGraph);
    }

    @Test
    void testFilterNodesWithAllProjectionIncluded() {
        GraphLoader graphLoader = new StoreLoaderBuilder()
            .api(db)
            .graphName("myGraph")
            .putNodeProjectionsWithIdentifier("A", NodeProjection.of("A", PropertyMappings.of()))
            .putNodeProjectionsWithIdentifier("All", NodeProjection.all())
            .loadAnyRelationshipType()
            .build();

        GraphStore graphStore = graphLoader.graphStore(NativeFactory.class);

        Graph filteredAGraph = graphStore.getGraph(
            Collections.singletonList(LABEL_A),
            graphStore.relationshipTypes(),
            Optional.empty(),
            1
        );

        assertGraphEquals(fromGdl("(a:A:All)"), filteredAGraph);

        Graph filteredAllGraph = graphStore.getGraph(
            Collections.singletonList(NodeLabel.of("All")),
            graphStore.relationshipTypes(),
            Optional.empty(),
            1
        );

        Graph nonFilteredGraph = graphStore.getUnion();

        assertGraphEquals(filteredAllGraph, nonFilteredGraph);
    }

    @Test
    void testModificationDate() throws InterruptedException {
        GraphStore graphStore = new StoreLoaderBuilder()
            .api(db)
            .loadAnyLabel()
            .loadAnyRelationshipType()
            .build()
            .graphStore(NativeFactory.class);

        // add node properties
        LocalDateTime initialTime = graphStore.modificationTime();
        Thread.sleep(42);
        graphStore.addNodeProperty(ALL_NODES, "foo", FLOATING_POINT, new NullPropertyMap(42.0));
        LocalDateTime nodePropertyTime = graphStore.modificationTime();

        // add relationships
        HugeGraph.Relationships relationships = HugeGraph.Relationships.of(
            0L,
            Orientation.NATURAL,
            new AdjacencyList(new byte[0][0]),
            AdjacencyOffsets.of(new long[0]),
            null,
            null,
            42.0
        );
        Thread.sleep(42);
        graphStore.addRelationshipType(RelationshipType.of("BAR"), Optional.empty(), Optional.empty(), relationships);
        LocalDateTime relationshipTime = graphStore.modificationTime();

        assertTrue(initialTime.isBefore(nodePropertyTime), "Node property update did not change modificationTime");
        assertTrue(nodePropertyTime.isBefore(relationshipTime), "Relationship update did not change modificationTime");
    }

    @Test
    void testRemoveNodeProperty() {
        runQuery("CREATE (a {nodeProp: 42})-[:REL]->(b {nodeProp: 23})");

        GraphStore graphStore = new StoreLoaderBuilder()
            .api(db)
            .loadAnyLabel()
            .addNodeProperty(PropertyMapping.of("nodeProp", 0D))
            .loadAnyRelationshipType()
            .build()
            .graphStore(NativeFactory.class);

        assertTrue(graphStore.hasNodeProperty(Collections.singletonList(ALL_NODES), "nodeProp"));
        graphStore.removeNodeProperty(ALL_NODES, "nodeProp");
        assertFalse(graphStore.hasNodeProperty(Collections.singletonList(ALL_NODES), "nodeProp"));
    }

    @Test
    void deleteRelationshipsAndProperties() {
        runQuery("CREATE ()-[:REL {p: 2}]->(), ()-[:LER {p: 1}]->(), ()-[:LER {p: 2}]->(), ()-[:LER {q: 2}]->()");

        GraphStore graphStore = new StoreLoaderBuilder()
            .api(db)
            .loadAnyLabel()
            .addRelationshipProjection(RelationshipProjection.of("REL", Orientation.NATURAL)
                .withProperties(
                    PropertyMappings.of(PropertyMapping.of("p", 3.14))
                )
            )
            .addRelationshipProjection(RelationshipProjection.of("LER", Orientation.NATURAL)
                .withProperties(
                    PropertyMappings.of(
                        PropertyMapping.of("p", 3.14),
                        PropertyMapping.of("q", 3.15)
                    )
                )
            )
            .build()
            .graphStore(NativeFactory.class);

        assertThat(graphStore.relationshipCount(), equalTo(4L));
        // should be 7, change this when we have fixed the issue with
        // relationshipTypes containing properties from other relationshipTypes
        assertThat(graphStore.relationshipPropertyCount(), equalTo(8L));

        DeletionResult deletionResult = graphStore.deleteRelationships(RelationshipType.of("LER"));

        assertEquals(new HashSet<>(singletonList(RelationshipType.of("REL"))), graphStore.relationshipTypes());
        assertFalse(graphStore.hasRelationshipType(RelationshipType.of("LER")));
        assertEquals(1, graphStore.relationshipCount());
        // should expect 1 instead of two, but currently properties are global across relationship types
        assertEquals(2, graphStore.relationshipPropertyCount());

        assertEquals(3, deletionResult.deletedRelationships());
        assertThat(deletionResult.deletedProperties(), mapEquals(map("p", 3L, "q", 3L)));
    }

    @NotNull
    private static List<NodeProjection> nodeProjections() {
        NodeProjection aMapping = NodeProjection.builder()
            .label("A")
            .properties(PropertyMappings.of(Arrays.asList(
                PropertyMapping.of("nodeProperty", -1D),
                PropertyMapping.of("a", -1D)
            )))
            .build();

        NodeProjection bMapping = NodeProjection.builder()
            .label("B")
            .properties(PropertyMappings.of(Arrays.asList(
                PropertyMapping.of("nodeProperty", -1D),
                PropertyMapping.of("b", -1D)
            )))
            .build();

        return Arrays.asList(aMapping, bMapping);
    }

    @NotNull
    private static List<RelationshipProjection> relationshipProjections() {
        RelationshipProjection t1Mapping = RelationshipProjection.builder()
            .type("T1")
            .orientation(Orientation.NATURAL)
            .aggregation(Aggregation.NONE)
            .properties(
                PropertyMappings.builder()
                    .addMapping("property1", "property1", 42D, Aggregation.NONE)
                    .addMapping("property2", "property2", 1337D, Aggregation.NONE)
                    .build()
            ).build();

        RelationshipProjection t2Mapping = RelationshipProjection.builder()
            .type("T2")
            .orientation(Orientation.NATURAL)
            .aggregation(Aggregation.NONE)
            .properties(
                PropertyMappings.builder()
                    .addMapping("property1", "property1", 42D, Aggregation.NONE)
                    .build()
            ).build();

        RelationshipProjection t3Mapping = RelationshipProjection.builder()
            .type("T3")
            .orientation(Orientation.NATURAL)
            .aggregation(Aggregation.NONE)
            .properties(
                PropertyMappings.builder()
                    .addMapping("property2", "property2", 42D, Aggregation.NONE)
                    .build()
            ).build();

        return Arrays.asList(t1Mapping, t2Mapping, t3Mapping);
    }

    static Stream<Arguments> validRelationshipFilterParameters() {
        return Stream.of(
            Arguments.of(
                "filterByRelationshipType",
                singletonList(RelationshipType.of("T1")),
                Optional.empty(),
                "(a:A), (b:B), (a)-[T1]->(b)"
            ),
            Arguments.of(
                "filterByMultipleRelationshipTypes",
                Arrays.asList(RelationshipType.of("T1"), RelationshipType.of("T2")),
                Optional.empty(),
                "(a:A), (b:B), (a)-[T1]->(b), (a)-[T2]->(b)"
            ),
            Arguments.of(
                "filterByAnyRelationshipType",
                Arrays.asList(RelationshipType.of("T1"), RelationshipType.of("T2"), RelationshipType.of("T3")),
                Optional.empty(),
                "(a:A), (b:B), (a)-[T1]->(b), (a)-[T2]->(b), (a)-[T3]->(b)"
            ),
            Arguments.of(
                "filterByRelationshipProperty",
                Arrays.asList(RelationshipType.of("T1"), RelationshipType.of("T2")),
                Optional.of("property1"),
                "(a:A), (b:B), (a)-[T1 {property1: 42}]->(b), (a)-[T2 {property1: 43}]->(b)"
            ),
            Arguments.of(
                "filterByRelationshipTypeAndProperty",
                singletonList(RelationshipType.of("T1")),
                Optional.of("property1"),
                "(a:A), (b:B), (a)-[T1 {property1: 42}]->(b)"
            ),
            /*
              As our graph loader is not capable of loading different relationship properties for different types
              it will still load property1 for T3.
              It seems that the default values it uses is taken from one of the other property mappings
              This test should be adapted once the loader is capable of loading the correct projections.
             */
            Arguments.of(
                "includeRelationshipTypesThatDoNotHaveTheProperty",
                Arrays.asList(RelationshipType.of("T1"), RelationshipType.of("T2"), RelationshipType.of("T3")),
                Optional.of("property1"),
                "(a:A), (b:B), (a)-[T1 {property1: 42}]->(b), (a)-[T2 {property1: 43}]->(b), (a)-[T3 {property1: 42.0}]->(b)"
            )
        );
    }

    static Stream<Arguments> validNodeFilterParameters() {
        return Stream.of(
            Arguments.of(
                "filterAllLabels",
                Arrays.asList(NodeLabel.of("A"), NodeLabel.of("B"), NodeLabel.of("Ignore")),
                "(a:A {nodeProperty: 33, a: 33, b: 'NaN'}), (b:B {nodeProperty: 42, a: 'NaN', b: 42}), (a)-[T1]->(b)"
            ),
            Arguments.of(
                "filterAllTypesExplicit",
                Arrays.asList(NodeLabel.of("A"), NodeLabel.of("B")),
                "(a:A {nodeProperty: 33, a: 33, b: 'NaN'}), (b:B {nodeProperty: 42, a: 'NaN', b: 42}), (a)-[T1]->(b)"
            ),
            Arguments.of(
                "FilterA",
                singletonList(NodeLabel.of("A")),
                "(a:A {nodeProperty: 33, a: 33})"
            ),
            Arguments.of(
                "FilterB",
                singletonList(NodeLabel.of("B")),
                "(b:B {nodeProperty: 42, b: 42})"
            )
        );
    }
}
