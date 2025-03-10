/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.kubernetes.operator.autoscaler;

import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.kubernetes.operator.autoscaler.config.AutoScalerOptions;
import org.apache.flink.kubernetes.operator.autoscaler.metrics.CollectedMetrics;
import org.apache.flink.kubernetes.operator.autoscaler.metrics.EvaluatedScalingMetric;
import org.apache.flink.kubernetes.operator.autoscaler.metrics.ScalingMetric;
import org.apache.flink.kubernetes.operator.autoscaler.topology.JobTopology;
import org.apache.flink.kubernetes.operator.autoscaler.topology.VertexInfo;
import org.apache.flink.runtime.jobgraph.JobVertexID;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.apache.flink.kubernetes.operator.autoscaler.config.AutoScalerOptions.CATCH_UP_DURATION;
import static org.apache.flink.kubernetes.operator.autoscaler.config.AutoScalerOptions.RESTART_TIME;
import static org.apache.flink.kubernetes.operator.autoscaler.config.AutoScalerOptions.TARGET_UTILIZATION;
import static org.apache.flink.kubernetes.operator.autoscaler.config.AutoScalerOptions.TARGET_UTILIZATION_BOUNDARY;
import static org.apache.flink.kubernetes.operator.autoscaler.metrics.ScalingMetric.CATCH_UP_DATA_RATE;
import static org.apache.flink.kubernetes.operator.autoscaler.metrics.ScalingMetric.LAG;
import static org.apache.flink.kubernetes.operator.autoscaler.metrics.ScalingMetric.OUTPUT_RATIO;
import static org.apache.flink.kubernetes.operator.autoscaler.metrics.ScalingMetric.SCALE_DOWN_RATE_THRESHOLD;
import static org.apache.flink.kubernetes.operator.autoscaler.metrics.ScalingMetric.SCALE_UP_RATE_THRESHOLD;
import static org.apache.flink.kubernetes.operator.autoscaler.metrics.ScalingMetric.SOURCE_DATA_RATE;
import static org.apache.flink.kubernetes.operator.autoscaler.metrics.ScalingMetric.TARGET_DATA_RATE;
import static org.apache.flink.kubernetes.operator.autoscaler.metrics.ScalingMetric.TRUE_OUTPUT_RATE;
import static org.apache.flink.kubernetes.operator.autoscaler.metrics.ScalingMetric.TRUE_PROCESSING_RATE;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Scaling evaluator test. */
public class ScalingMetricEvaluatorTest {

    @Test
    public void testLagBasedSourceScaling() {
        var source = new JobVertexID();
        var sink = new JobVertexID();

        var topology =
                new JobTopology(
                        new VertexInfo(source, Collections.emptySet(), 1, 1),
                        new VertexInfo(sink, Set.of(source), 1, 1));

        var evaluator = new ScalingMetricEvaluator();

        var metricHistory = new TreeMap<Instant, Map<JobVertexID, Map<ScalingMetric, Double>>>();

        metricHistory.put(
                Instant.now(),
                Map.of(
                        source,
                        Map.of(
                                SOURCE_DATA_RATE,
                                100.,
                                LAG,
                                0.,
                                OUTPUT_RATIO,
                                2.,
                                TRUE_OUTPUT_RATE,
                                200.,
                                TRUE_PROCESSING_RATE,
                                200.),
                        sink,
                        Map.of(TRUE_PROCESSING_RATE, 2000.)));

        metricHistory.put(
                Instant.now(),
                Map.of(
                        source,
                        Map.of(
                                SOURCE_DATA_RATE, 200.,
                                LAG, 1000.,
                                OUTPUT_RATIO, 2.,
                                TRUE_OUTPUT_RATE, 200.,
                                TRUE_PROCESSING_RATE, 200.),
                        sink,
                        Map.of(TRUE_PROCESSING_RATE, 2000.)));

        var conf = new Configuration();

        conf.set(AutoScalerOptions.CATCH_UP_DURATION, Duration.ofSeconds(2));
        conf.set(AutoScalerOptions.RESTART_TIME, Duration.ZERO);
        var evaluatedMetrics =
                evaluator.evaluate(conf, new CollectedMetrics(topology, metricHistory));
        assertEquals(
                new EvaluatedScalingMetric(200, 150),
                evaluatedMetrics.get(source).get(TARGET_DATA_RATE));
        assertEquals(
                EvaluatedScalingMetric.of(500),
                evaluatedMetrics.get(source).get(CATCH_UP_DATA_RATE));
        assertEquals(
                new EvaluatedScalingMetric(400, 300),
                evaluatedMetrics.get(sink).get(TARGET_DATA_RATE));
        assertEquals(
                EvaluatedScalingMetric.of(1000),
                evaluatedMetrics.get(sink).get(CATCH_UP_DATA_RATE));

        conf.set(AutoScalerOptions.CATCH_UP_DURATION, Duration.ofSeconds(1));
        evaluatedMetrics = evaluator.evaluate(conf, new CollectedMetrics(topology, metricHistory));
        assertEquals(
                new EvaluatedScalingMetric(200, 150),
                evaluatedMetrics.get(source).get(TARGET_DATA_RATE));
        assertEquals(
                EvaluatedScalingMetric.of(1000),
                evaluatedMetrics.get(source).get(CATCH_UP_DATA_RATE));
        assertEquals(
                new EvaluatedScalingMetric(400, 300),
                evaluatedMetrics.get(sink).get(TARGET_DATA_RATE));
        assertEquals(
                EvaluatedScalingMetric.of(2000),
                evaluatedMetrics.get(sink).get(CATCH_UP_DATA_RATE));

        // Restart time should not affect evaluated metrics
        conf.set(AutoScalerOptions.RESTART_TIME, Duration.ofSeconds(2));

        evaluatedMetrics = evaluator.evaluate(conf, new CollectedMetrics(topology, metricHistory));
        assertEquals(
                new EvaluatedScalingMetric(200, 150),
                evaluatedMetrics.get(source).get(TARGET_DATA_RATE));
        assertEquals(
                EvaluatedScalingMetric.of(1000),
                evaluatedMetrics.get(source).get(CATCH_UP_DATA_RATE));
        assertEquals(
                new EvaluatedScalingMetric(400, 300),
                evaluatedMetrics.get(sink).get(TARGET_DATA_RATE));
        assertEquals(
                EvaluatedScalingMetric.of(2000),
                evaluatedMetrics.get(sink).get(CATCH_UP_DATA_RATE));

        // Turn off lag based scaling
        conf.set(AutoScalerOptions.CATCH_UP_DURATION, Duration.ZERO);
        evaluatedMetrics = evaluator.evaluate(conf, new CollectedMetrics(topology, metricHistory));
        assertEquals(
                new EvaluatedScalingMetric(200, 150),
                evaluatedMetrics.get(source).get(TARGET_DATA_RATE));
        assertEquals(
                EvaluatedScalingMetric.of(0), evaluatedMetrics.get(source).get(CATCH_UP_DATA_RATE));
        assertEquals(
                new EvaluatedScalingMetric(400, 300),
                evaluatedMetrics.get(sink).get(TARGET_DATA_RATE));
        assertEquals(
                EvaluatedScalingMetric.of(0), evaluatedMetrics.get(sink).get(CATCH_UP_DATA_RATE));

        // Test 0 lag
        metricHistory.clear();
        metricHistory.put(
                Instant.now(),
                Map.of(
                        source,
                        Map.of(
                                SOURCE_DATA_RATE,
                                100.,
                                LAG,
                                0.,
                                OUTPUT_RATIO,
                                2.,
                                TRUE_OUTPUT_RATE,
                                200.,
                                TRUE_PROCESSING_RATE,
                                200.),
                        sink,
                        Map.of(TRUE_PROCESSING_RATE, 2000.)));

        conf.set(AutoScalerOptions.CATCH_UP_DURATION, Duration.ofMinutes(1));
        evaluatedMetrics = evaluator.evaluate(conf, new CollectedMetrics(topology, metricHistory));
        assertEquals(
                new EvaluatedScalingMetric(100, 100),
                evaluatedMetrics.get(source).get(TARGET_DATA_RATE));
        assertEquals(
                new EvaluatedScalingMetric(200, 200),
                evaluatedMetrics.get(sink).get(TARGET_DATA_RATE));
    }

    @Test
    public void testUtilizationBoundaryComputation() {

        var conf = new Configuration();
        conf.set(TARGET_UTILIZATION, 0.8);
        conf.set(TARGET_UTILIZATION_BOUNDARY, 0.1);
        conf.set(RESTART_TIME, Duration.ofSeconds(1));
        conf.set(CATCH_UP_DURATION, Duration.ZERO);

        // Default behaviour, restart time does not factor in
        assertEquals(Tuple2.of(778.0, 1000.0), getThresholds(700, 0, conf));

        conf.set(CATCH_UP_DURATION, Duration.ofSeconds(2));
        assertEquals(Tuple2.of(1128.0, 1700.0), getThresholds(700, 350, conf));
        assertEquals(Tuple2.of(778.0, 1350.0), getThresholds(700, 0, conf));
    }

    private Tuple2<Double, Double> getThresholds(
            double inputTargetRate, double catchUpRate, Configuration conf) {
        var map = new HashMap<ScalingMetric, EvaluatedScalingMetric>();

        map.put(TARGET_DATA_RATE, new EvaluatedScalingMetric(Double.NaN, inputTargetRate));
        map.put(CATCH_UP_DATA_RATE, EvaluatedScalingMetric.of(catchUpRate));

        ScalingMetricEvaluator.computeProcessingRateThresholds(map, conf);
        return Tuple2.of(
                map.get(SCALE_UP_RATE_THRESHOLD).getCurrent(),
                map.get(SCALE_DOWN_RATE_THRESHOLD).getCurrent());
    }
}
