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
package org.neo4j.graphalgo.labelpropagation;

import org.neo4j.graphalgo.AlgorithmFactory;
import org.neo4j.graphalgo.WriteProc;
import org.neo4j.graphalgo.api.NodeProperties;
import org.neo4j.graphalgo.config.GraphCreateConfig;
import org.neo4j.graphalgo.core.CypherMapWrapper;
import org.neo4j.graphalgo.core.utils.paged.AllocationTracker;
import org.neo4j.graphalgo.core.write.PropertyTranslator;
import org.neo4j.graphalgo.result.AbstractResultBuilder;
import org.neo4j.graphalgo.results.MemoryEstimateResult;
import org.neo4j.internal.kernel.api.procs.ProcedureCallContext;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.neo4j.graphalgo.labelpropagation.LabelPropagationProc.LABEL_PROPAGATION_DESCRIPTION;
import static org.neo4j.procedure.Mode.READ;
import static org.neo4j.procedure.Mode.WRITE;

public class LabelPropagationWriteProc extends WriteProc<LabelPropagation, LabelPropagation, LabelPropagationWriteProc.WriteResult, LabelPropagationWriteConfig> {

    @Procedure(value = "gds.labelPropagation.write", mode = WRITE)
    @Description(LABEL_PROPAGATION_DESCRIPTION)
    public Stream<WriteResult> write(
        @Name(value = "graphName") Object graphNameOrConfig,
        @Name(value = "configuration", defaultValue = "{}") Map<String, Object> configuration
    ) {
        ComputationResult<LabelPropagation, LabelPropagation, LabelPropagationWriteConfig> result = compute(
            graphNameOrConfig,
            configuration
        );
        return write(result);
    }

    @Procedure(value = "gds.labelPropagation.write.estimate", mode = READ)
    @Description(ESTIMATE_DESCRIPTION)
    public Stream<MemoryEstimateResult> estimate(
        @Name(value = "graphName") Object graphNameOrConfig,
        @Name(value = "configuration", defaultValue = "{}") Map<String, Object> configuration
    ) {
        return computeEstimate(graphNameOrConfig, configuration);
    }

    @Override
    protected PropertyTranslator<LabelPropagation> nodePropertyTranslator(ComputationResult<LabelPropagation, LabelPropagation, LabelPropagationWriteConfig> computationResult) {

        LabelPropagationWriteConfig config = computationResult.config();

        boolean writePropertyEqualsSeedProperty = config.seedProperty() != null && config
            .writeProperty()
            .equals(config.seedProperty());

        if (writePropertyEqualsSeedProperty) {
            NodeProperties seedProperties = computationResult.graph().nodeProperties(config.seedProperty());
            return new PropertyTranslator.OfLongIfChanged<>(
                seedProperties,
                (data, nodeId) -> data.labels().get(nodeId)
            );
        }

        return (PropertyTranslator.OfLong<LabelPropagation>) (data, nodeId) -> data
            .labels()
            .get(nodeId);
    }

    @Override
    protected AbstractResultBuilder<WriteResult> resultBuilder(ComputationResult<LabelPropagation, LabelPropagation, LabelPropagationWriteConfig> computeResult) {
        return LabelPropagationProc.resultBuilder(
            new WriteResult.Builder(computeResult.graph().nodeCount(), callContext, computeResult.tracker()),
            computeResult
        );
    }

    @Override
    public LabelPropagationWriteConfig newConfig(
        String username,
        Optional<String> graphName,
        Optional<GraphCreateConfig> maybeImplicitCreate,
        CypherMapWrapper config
    ) {
        return LabelPropagationWriteConfig.of(username, graphName, maybeImplicitCreate, config);
    }

    @Override
    protected AlgorithmFactory<LabelPropagation, LabelPropagationWriteConfig> algorithmFactory(
        LabelPropagationWriteConfig config
    ) {
        return new LabelPropagationFactory<>(config);
    }

    public static class WriteResult {

        public long nodePropertiesWritten;
        public long createMillis;
        public long computeMillis;
        public long writeMillis;
        public long postProcessingMillis;
        public long communityCount;
        public long ranIterations;
        public boolean didConverge;
        public Map<String, Object> communityDistribution;
        public Map<String, Object> configuration;

        WriteResult(
            long nodePropertiesWritten,
            long createMillis,
            long computeMillis,
            long writeMillis,
            long postProcessingMillis,
            long communityCount,
            long ranIterations,
            boolean didConverge,
            Map<String, Object> communityDistribution,
            Map<String, Object> configuration
        ) {
            this.nodePropertiesWritten = nodePropertiesWritten;
            this.createMillis = createMillis;
            this.computeMillis = computeMillis;
            this.writeMillis = writeMillis;
            this.postProcessingMillis = postProcessingMillis;
            this.communityCount = communityCount;
            this.ranIterations = ranIterations;
            this.didConverge = didConverge;
            this.communityDistribution = communityDistribution;
            this.configuration = configuration;
        }

        static class Builder extends LabelPropagationProc.LabelPropagationResultBuilder<WriteResult> {

            Builder(
                long nodeCount,
                ProcedureCallContext context,
                AllocationTracker tracker
            ) {
                super(
                    nodeCount,
                    context,
                    tracker
                );
            }

            @Override
            protected WriteResult buildResult() {
                return new WriteResult(
                    nodePropertiesWritten,
                    createMillis,
                    computeMillis,
                    writeMillis,
                    postProcessingDuration,
                    maybeCommunityCount.orElse(-1L),
                    ranIterations,
                    didConverge,
                    communityHistogramOrNull(),
                    config.toMap()
                );
            }
        }
    }

}
