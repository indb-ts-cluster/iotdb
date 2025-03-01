/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.queryengine.plan.planner;

import org.apache.iotdb.common.rpc.thrift.TAggregationType;
import org.apache.iotdb.common.rpc.thrift.TDataNodeLocation;
import org.apache.iotdb.common.rpc.thrift.TSchemaNode;
import org.apache.iotdb.commons.exception.IllegalPathException;
import org.apache.iotdb.commons.exception.MetadataException;
import org.apache.iotdb.commons.path.AlignedPath;
import org.apache.iotdb.commons.path.MeasurementPath;
import org.apache.iotdb.commons.path.PartialPath;
import org.apache.iotdb.commons.path.PathPatternTree;
import org.apache.iotdb.commons.schema.filter.SchemaFilter;
import org.apache.iotdb.db.queryengine.common.MPPQueryContext;
import org.apache.iotdb.db.queryengine.common.header.ColumnHeaderConstant;
import org.apache.iotdb.db.queryengine.execution.aggregation.AccumulatorFactory;
import org.apache.iotdb.db.queryengine.execution.operator.AggregationUtil;
import org.apache.iotdb.db.queryengine.plan.analyze.Analysis;
import org.apache.iotdb.db.queryengine.plan.analyze.ExpressionAnalyzer;
import org.apache.iotdb.db.queryengine.plan.analyze.TypeProvider;
import org.apache.iotdb.db.queryengine.plan.expression.Expression;
import org.apache.iotdb.db.queryengine.plan.expression.leaf.TimeSeriesOperand;
import org.apache.iotdb.db.queryengine.plan.expression.multi.FunctionExpression;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.PlanNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.metedata.read.CountSchemaMergeNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.metedata.read.DevicesCountNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.metedata.read.DevicesSchemaScanNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.metedata.read.LevelTimeSeriesCountNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.metedata.read.LogicalViewSchemaScanNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.metedata.read.NodeManagementMemoryMergeNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.metedata.read.NodePathsConvertNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.metedata.read.NodePathsCountNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.metedata.read.NodePathsSchemaScanNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.metedata.read.PathsUsingTemplateScanNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.metedata.read.SchemaFetchMergeNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.metedata.read.SchemaFetchScanNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.metedata.read.SchemaQueryMergeNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.metedata.read.SchemaQueryOrderByHeatNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.metedata.read.TimeSeriesCountNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.metedata.read.TimeSeriesSchemaScanNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.process.AggregationNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.process.ColumnInjectNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.process.DeviceViewIntoNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.process.DeviceViewNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.process.FillNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.process.FilterNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.process.GroupByLevelNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.process.GroupByTagNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.process.IntoNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.process.LimitNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.process.MergeSortNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.process.MultiChildProcessNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.process.OffsetNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.process.SingleDeviceViewNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.process.SlidingWindowAggregationNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.process.SortNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.process.TopKNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.process.TransformNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.process.join.FullOuterTimeJoinNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.process.last.LastQueryNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.process.last.LastQueryTransformNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.source.AlignedLastQueryScanNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.source.AlignedSeriesAggregationScanNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.source.AlignedSeriesScanNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.source.LastQueryScanNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.source.SeriesAggregationScanNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.source.SeriesAggregationSourceNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.source.SeriesScanNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.source.ShowQueriesNode;
import org.apache.iotdb.db.queryengine.plan.planner.plan.parameter.AggregationDescriptor;
import org.apache.iotdb.db.queryengine.plan.planner.plan.parameter.AggregationStep;
import org.apache.iotdb.db.queryengine.plan.planner.plan.parameter.CrossSeriesAggregationDescriptor;
import org.apache.iotdb.db.queryengine.plan.planner.plan.parameter.DeviceViewIntoPathDescriptor;
import org.apache.iotdb.db.queryengine.plan.planner.plan.parameter.FillDescriptor;
import org.apache.iotdb.db.queryengine.plan.planner.plan.parameter.GroupByParameter;
import org.apache.iotdb.db.queryengine.plan.planner.plan.parameter.GroupByTimeParameter;
import org.apache.iotdb.db.queryengine.plan.planner.plan.parameter.IntoPathDescriptor;
import org.apache.iotdb.db.queryengine.plan.planner.plan.parameter.OrderByParameter;
import org.apache.iotdb.db.queryengine.plan.statement.component.OrderByKey;
import org.apache.iotdb.db.queryengine.plan.statement.component.Ordering;
import org.apache.iotdb.db.queryengine.plan.statement.component.SortItem;
import org.apache.iotdb.db.queryengine.plan.statement.crud.QueryStatement;
import org.apache.iotdb.db.queryengine.plan.statement.sys.ShowQueriesStatement;
import org.apache.iotdb.db.schemaengine.schemaregion.utils.MetaUtils;
import org.apache.iotdb.db.schemaengine.template.Template;
import org.apache.iotdb.db.utils.SchemaUtils;
import org.apache.iotdb.db.utils.columngenerator.parameter.SlidingTimeColumnGeneratorParameter;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.utils.Pair;
import org.apache.iotdb.tsfile.write.schema.IMeasurementSchema;

import org.apache.commons.lang3.Validate;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static org.apache.iotdb.commons.conf.IoTDBConstant.MULTI_LEVEL_PATH_WILDCARD;
import static org.apache.iotdb.db.queryengine.common.header.ColumnHeaderConstant.DEVICE;
import static org.apache.iotdb.db.queryengine.common.header.ColumnHeaderConstant.ENDTIME;
import static org.apache.iotdb.db.queryengine.plan.analyze.ExpressionTypeAnalyzer.analyzeExpression;
import static org.apache.iotdb.db.queryengine.plan.planner.plan.node.process.TopKNode.LIMIT_VALUE_USE_TOP_K;
import static org.apache.iotdb.db.queryengine.plan.statement.component.FillPolicy.LINEAR;
import static org.apache.iotdb.db.utils.constant.SqlConstant.COUNT_TIME;
import static org.apache.iotdb.db.utils.constant.SqlConstant.LAST_VALUE;
import static org.apache.iotdb.db.utils.constant.SqlConstant.MAX_TIME;

public class LogicalPlanBuilder {

  protected PlanNode root;

  private final MPPQueryContext context;

  private final Function<Expression, TSDataType> getPreAnalyzedType;

  public LogicalPlanBuilder(Analysis analysis, MPPQueryContext context) {
    this.getPreAnalyzedType = analysis::getType;
    this.context = context;
  }

  public PlanNode getRoot() {
    return root;
  }

  public LogicalPlanBuilder withNewRoot(PlanNode newRoot) {
    this.root = newRoot;
    return this;
  }

  void updateTypeProvider(Collection<Expression> expressions) {
    if (expressions == null) {
      return;
    }
    expressions.forEach(
        expression -> {
          if (!expression.getExpressionString().equals(DEVICE)
              && !expression.getExpressionString().equals(ENDTIME)) {
            context
                .getTypeProvider()
                .setType(expression.getExpressionString(), getPreAnalyzedType.apply(expression));
          }
        });
  }

  private void updateTypeProviderWithConstantType(List<String> keys, TSDataType dataType) {
    if (keys == null) {
      return;
    }
    keys.forEach(k -> context.getTypeProvider().setType(k, dataType));
  }

  private void updateTypeProviderWithConstantType(String columnName, TSDataType dataType) {
    context.getTypeProvider().setType(columnName, dataType);
  }

  public LogicalPlanBuilder planRawDataSource(
      Set<Expression> sourceExpressions,
      Ordering scanOrder,
      long offset,
      long limit,
      boolean lastLevelUseWildcard) {
    List<PlanNode> sourceNodeList = new ArrayList<>();
    List<PartialPath> selectedPaths =
        sourceExpressions.stream()
            .map(expression -> ((TimeSeriesOperand) expression).getPath())
            .collect(Collectors.toList());
    List<PartialPath> groupedPaths = MetaUtils.groupAlignedSeries(selectedPaths);
    for (PartialPath path : groupedPaths) {
      if (path instanceof MeasurementPath) {
        // non-aligned series
        SeriesScanNode seriesScanNode =
            new SeriesScanNode(
                context.getQueryId().genPlanNodeId(),
                (MeasurementPath) path,
                scanOrder,
                limit,
                offset,
                null);
        sourceNodeList.add(seriesScanNode);
      } else if (path instanceof AlignedPath) {
        // aligned series
        AlignedSeriesScanNode alignedSeriesScanNode =
            new AlignedSeriesScanNode(
                context.getQueryId().genPlanNodeId(),
                (AlignedPath) path,
                scanOrder,
                limit,
                offset,
                null,
                lastLevelUseWildcard);
        sourceNodeList.add(alignedSeriesScanNode);
      } else {
        throw new IllegalArgumentException("Unexpected path type");
      }
    }

    updateTypeProvider(sourceExpressions);

    this.root = convergeWithTimeJoin(sourceNodeList, scanOrder);
    return this;
  }

  public LogicalPlanBuilder planLast(
      Analysis analysis, Ordering timeseriesOrdering, ZoneId zoneId) {
    Set<String> deviceAlignedSet = new HashSet<>();
    Set<String> deviceExistViewSet = new HashSet<>();
    // <Device, <Measurement, Expression>>
    Map<String, Map<String, Expression>> outputPathToSourceExpressionMap = new LinkedHashMap<>();

    for (Expression sourceExpression : analysis.getLastQueryBaseExpressions()) {
      MeasurementPath outputPath =
          (MeasurementPath)
              (sourceExpression.isViewExpression()
                  ? sourceExpression.getViewPath()
                  : ((TimeSeriesOperand) sourceExpression).getPath());
      String outputDevice = outputPath.getDevice();
      outputPathToSourceExpressionMap
          .computeIfAbsent(
              outputDevice,
              k ->
                  timeseriesOrdering != null
                      ? new TreeMap<>(timeseriesOrdering.getStringComparator())
                      : new LinkedHashMap<>())
          .put(outputPath.getMeasurement(), sourceExpression);
      if (outputPath.isUnderAlignedEntity()) {
        deviceAlignedSet.add(outputDevice);
      }
      if (sourceExpression.isViewExpression()) {
        deviceExistViewSet.add(outputDevice);
      }
    }

    List<PlanNode> sourceNodeList = new ArrayList<>();
    for (Map.Entry<String, Map<String, Expression>> deviceMeasurementExpressionEntry :
        outputPathToSourceExpressionMap.entrySet()) {
      String outputDevice = deviceMeasurementExpressionEntry.getKey();
      Map<String, Expression> measurementToExpressionsOfDevice =
          deviceMeasurementExpressionEntry.getValue();
      if (deviceExistViewSet.contains(outputDevice)) {
        // exist view
        for (Expression sourceExpression : measurementToExpressionsOfDevice.values()) {
          MeasurementPath selectedPath =
              (MeasurementPath) ((TimeSeriesOperand) sourceExpression).getPath();
          String outputViewPath =
              sourceExpression.isViewExpression()
                  ? sourceExpression.getViewPath().getFullPath()
                  : null;

          if (selectedPath.isUnderAlignedEntity()) { // aligned series
            sourceNodeList.add(
                new AlignedLastQueryScanNode(
                    context.getQueryId().genPlanNodeId(),
                    new AlignedPath(selectedPath),
                    outputViewPath));
          } else { // non-aligned series
            sourceNodeList.add(
                new LastQueryScanNode(
                    context.getQueryId().genPlanNodeId(), selectedPath, outputViewPath));
          }
        }
      } else {
        if (deviceAlignedSet.contains(outputDevice)) {
          // aligned series
          List<MeasurementPath> measurementPaths =
              measurementToExpressionsOfDevice.values().stream()
                  .map(expression -> (MeasurementPath) ((TimeSeriesOperand) expression).getPath())
                  .collect(Collectors.toList());
          AlignedPath alignedPath = new AlignedPath(measurementPaths.get(0).getDevicePath());
          for (MeasurementPath measurementPath : measurementPaths) {
            alignedPath.addMeasurement(measurementPath);
          }
          sourceNodeList.add(
              new AlignedLastQueryScanNode(
                  context.getQueryId().genPlanNodeId(), alignedPath, null));
        } else {
          // non-aligned series
          for (Expression sourceExpression : measurementToExpressionsOfDevice.values()) {
            MeasurementPath selectedPath =
                (MeasurementPath) ((TimeSeriesOperand) sourceExpression).getPath();
            sourceNodeList.add(
                new LastQueryScanNode(context.getQueryId().genPlanNodeId(), selectedPath, null));
          }
        }
      }
    }

    processLastQueryTransformNode(analysis, sourceNodeList, zoneId);

    if (timeseriesOrdering != null) {
      sourceNodeList.sort(
          Comparator.comparing(
              child -> {
                String sortKey = "";
                if (child instanceof LastQueryScanNode) {
                  sortKey = ((LastQueryScanNode) child).getOutputSymbolForSort();
                } else if (child instanceof AlignedLastQueryScanNode) {
                  sortKey = ((AlignedLastQueryScanNode) child).getOutputSymbolForSort();
                } else if (child instanceof LastQueryTransformNode) {
                  sortKey = ((LastQueryTransformNode) child).getOutputSymbolForSort();
                }
                return sortKey;
              }));
      if (timeseriesOrdering.equals(Ordering.DESC)) {
        Collections.reverse(sourceNodeList);
      }
    }

    this.root =
        new LastQueryNode(
            context.getQueryId().genPlanNodeId(),
            sourceNodeList,
            timeseriesOrdering,
            analysis.getLastQueryNonWritableViewSourceExpressionMap() != null);

    ColumnHeaderConstant.lastQueryColumnHeaders.forEach(
        columnHeader ->
            context
                .getTypeProvider()
                .setType(columnHeader.getColumnName(), columnHeader.getColumnType()));

    return this;
  }

  private void processLastQueryTransformNode(
      Analysis analysis, List<PlanNode> sourceNodeList, ZoneId zoneId) {
    if (analysis.getLastQueryNonWritableViewSourceExpressionMap() == null) {
      return;
    }

    for (Map.Entry<Expression, List<Expression>> entry :
        analysis.getLastQueryNonWritableViewSourceExpressionMap().entrySet()) {
      Expression expression = entry.getKey();
      Set<Expression> sourceExpressions = new LinkedHashSet<>(entry.getValue());
      Set<Expression> sourceTransformExpressions = Collections.singleton(expression);
      FunctionExpression maxTimeAgg =
          new FunctionExpression(
              MAX_TIME, new LinkedHashMap<>(), Collections.singletonList(expression));
      FunctionExpression lastValueAgg =
          new FunctionExpression(
              LAST_VALUE, new LinkedHashMap<>(), Collections.singletonList(expression));
      analyzeExpression(analysis, expression);
      analyzeExpression(analysis, maxTimeAgg);
      analyzeExpression(analysis, lastValueAgg);

      LogicalPlanBuilder planBuilder = new LogicalPlanBuilder(analysis, context);
      planBuilder =
          planBuilder
              .planRawDataSource(
                  sourceExpressions, Ordering.DESC, 0, 0, analysis.isLastLevelUseWildcard())
              .planWhereAndSourceTransform(
                  null, sourceTransformExpressions, false, zoneId, Ordering.DESC)
              .planAggregation(
                  new LinkedHashSet<>(Arrays.asList(maxTimeAgg, lastValueAgg)),
                  null,
                  analysis.getGroupByTimeParameter(),
                  analysis.getGroupByParameter(),
                  false,
                  AggregationStep.SINGLE,
                  Ordering.DESC);

      LastQueryTransformNode transformNode =
          new LastQueryTransformNode(
              context.getQueryId().genPlanNodeId(),
              planBuilder.getRoot(),
              expression.getViewPath().getFullPath(),
              analysis.getType(expression).toString());
      sourceNodeList.add(transformNode);
    }
  }

  public LogicalPlanBuilder planAggregationSource(
      AggregationStep curStep,
      Ordering scanOrder,
      GroupByTimeParameter groupByTimeParameter,
      Set<Expression> aggregationExpressions,
      Set<Expression> sourceTransformExpressions,
      Map<Expression, Set<Expression>> crossGroupByAggregations,
      List<String> tagKeys,
      Map<List<String>, LinkedHashMap<Expression, List<Expression>>>
          tagValuesToGroupedTimeseriesOperands) {
    boolean needCheckAscending = groupByTimeParameter == null;
    Map<PartialPath, List<AggregationDescriptor>> ascendingAggregations = new HashMap<>();
    Map<PartialPath, List<AggregationDescriptor>> descendingAggregations = new HashMap<>();
    Map<PartialPath, List<AggregationDescriptor>> countTimeAggregations = new HashMap<>();
    for (Expression aggregationExpression : aggregationExpressions) {
      createAggregationDescriptor(
          (FunctionExpression) aggregationExpression,
          curStep,
          scanOrder,
          needCheckAscending,
          ascendingAggregations,
          descendingAggregations,
          countTimeAggregations);
    }

    List<PlanNode> sourceNodeList =
        constructSourceNodeFromAggregationDescriptors(
            ascendingAggregations,
            descendingAggregations,
            countTimeAggregations,
            scanOrder,
            groupByTimeParameter);
    updateTypeProvider(aggregationExpressions);
    updateTypeProvider(sourceTransformExpressions);

    return convergeAggregationSource(
        sourceNodeList,
        curStep,
        scanOrder,
        groupByTimeParameter,
        aggregationExpressions,
        crossGroupByAggregations,
        tagKeys,
        tagValuesToGroupedTimeseriesOperands);
  }

  public LogicalPlanBuilder planAggregationSourceWithIndexAdjust(
      AggregationStep curStep,
      Ordering scanOrder,
      GroupByTimeParameter groupByTimeParameter,
      Set<Expression> aggregationExpressions,
      Set<Expression> sourceTransformExpressions,
      Map<Expression, Set<Expression>> crossGroupByExpressions,
      List<Integer> deviceViewInputIndexes,
      boolean outputEndTime) {
    checkArgument(
        aggregationExpressions.size() <= deviceViewInputIndexes.size(),
        "Each aggregate should correspond to a column of output.");

    boolean needCheckAscending = groupByTimeParameter == null;
    Map<PartialPath, List<AggregationDescriptor>> ascendingAggregations = new HashMap<>();
    Map<PartialPath, List<AggregationDescriptor>> descendingAggregations = new HashMap<>();
    Map<AggregationDescriptor, Integer> aggregationToIndexMap = new HashMap<>();
    Map<PartialPath, List<AggregationDescriptor>> countTimeAggregations = new HashMap<>();

    // If need output endTime, the first index is used by __endTime
    int index = outputEndTime ? 1 : 0;
    for (Expression aggregationExpression : aggregationExpressions) {
      AggregationDescriptor aggregationDescriptor =
          createAggregationDescriptor(
              (FunctionExpression) aggregationExpression,
              curStep,
              scanOrder,
              needCheckAscending,
              ascendingAggregations,
              descendingAggregations,
              countTimeAggregations);
      aggregationToIndexMap.put(aggregationDescriptor, deviceViewInputIndexes.get(index));
      index++;
    }

    List<PlanNode> sourceNodeList =
        constructSourceNodeFromAggregationDescriptors(
            ascendingAggregations,
            descendingAggregations,
            countTimeAggregations,
            scanOrder,
            groupByTimeParameter);
    updateTypeProvider(aggregationExpressions);
    updateTypeProvider(sourceTransformExpressions);

    if (!curStep.isOutputPartial()) {
      // update measurementIndexes
      deviceViewInputIndexes.clear();
      if (outputEndTime) {
        deviceViewInputIndexes.add(1);
      }
      deviceViewInputIndexes.addAll(
          sourceNodeList.stream()
              .map(
                  planNode ->
                      ((SeriesAggregationSourceNode) planNode).getAggregationDescriptorList())
              .flatMap(List::stream)
              .map(aggregationToIndexMap::get)
              .collect(Collectors.toList()));
    }

    return convergeAggregationSource(
        sourceNodeList,
        curStep,
        scanOrder,
        groupByTimeParameter,
        aggregationExpressions,
        crossGroupByExpressions,
        null,
        null);
  }

  private AggregationDescriptor createAggregationDescriptor(
      FunctionExpression sourceExpression,
      AggregationStep curStep,
      Ordering scanOrder,
      boolean needCheckAscending,
      Map<PartialPath, List<AggregationDescriptor>> ascendingAggregations,
      Map<PartialPath, List<AggregationDescriptor>> descendingAggregations,
      Map<PartialPath, List<AggregationDescriptor>> countTimeAggregations) {
    AggregationDescriptor aggregationDescriptor =
        new AggregationDescriptor(
            sourceExpression.getFunctionName(),
            curStep,
            sourceExpression.getExpressions(),
            sourceExpression.getFunctionAttributes());
    if (curStep.isOutputPartial()) {
      updateTypeProviderByPartialAggregation(aggregationDescriptor, context.getTypeProvider());
    }

    if (COUNT_TIME.equalsIgnoreCase(sourceExpression.getFunctionName())) {
      Map<String, Pair<List<String>, List<IMeasurementSchema>>> map = new HashMap<>();
      for (Expression expression : sourceExpression.getCountTimeExpressions()) {
        TimeSeriesOperand ts = (TimeSeriesOperand) expression;
        PartialPath path = ts.getPath();
        Pair<List<String>, List<IMeasurementSchema>> pair =
            map.computeIfAbsent(
                path.getDevice(), k -> new Pair<>(new ArrayList<>(), new ArrayList<>()));
        pair.left.add(path.getMeasurement());
        try {
          pair.right.add(path.getMeasurementSchema());
        } catch (MetadataException ex) {
          throw new RuntimeException(ex);
        }
      }

      for (Map.Entry<String, Pair<List<String>, List<IMeasurementSchema>>> entry : map.entrySet()) {
        String device = entry.getKey();
        Pair<List<String>, List<IMeasurementSchema>> pair = entry.getValue();
        AlignedPath alignedPath = null;
        try {
          alignedPath = new AlignedPath(device, pair.left, pair.right);
        } catch (IllegalPathException e) {
          throw new RuntimeException(e);
        }
        countTimeAggregations.put(alignedPath, Collections.singletonList(aggregationDescriptor));
      }

      return aggregationDescriptor;
    }

    PartialPath selectPath =
        ((TimeSeriesOperand) sourceExpression.getExpressions().get(0)).getPath();
    if (!needCheckAscending
        || SchemaUtils.isConsistentWithScanOrder(
            aggregationDescriptor.getAggregationType(), scanOrder)) {
      ascendingAggregations
          .computeIfAbsent(selectPath, key -> new ArrayList<>())
          .add(aggregationDescriptor);
    } else {
      descendingAggregations
          .computeIfAbsent(selectPath, key -> new ArrayList<>())
          .add(aggregationDescriptor);
    }
    return aggregationDescriptor;
  }

  private List<PlanNode> constructSourceNodeFromAggregationDescriptors(
      Map<PartialPath, List<AggregationDescriptor>> ascendingAggregations,
      Map<PartialPath, List<AggregationDescriptor>> descendingAggregations,
      Map<PartialPath, List<AggregationDescriptor>> countTimeAggregations,
      Ordering scanOrder,
      GroupByTimeParameter groupByTimeParameter) {

    List<PlanNode> sourceNodeList = new ArrayList<>();
    boolean needCheckAscending = groupByTimeParameter == null;
    Map<PartialPath, List<AggregationDescriptor>> groupedAscendingAggregations = null;
    if (!countTimeAggregations.isEmpty()) {
      groupedAscendingAggregations = countTimeAggregations;
    } else {
      groupedAscendingAggregations = MetaUtils.groupAlignedAggregations(ascendingAggregations);
    }

    for (Map.Entry<PartialPath, List<AggregationDescriptor>> pathAggregationsEntry :
        groupedAscendingAggregations.entrySet()) {
      sourceNodeList.add(
          createAggregationScanNode(
              pathAggregationsEntry.getKey(),
              pathAggregationsEntry.getValue(),
              scanOrder,
              groupByTimeParameter));
    }

    if (needCheckAscending) {
      Map<PartialPath, List<AggregationDescriptor>> groupedDescendingAggregations =
          MetaUtils.groupAlignedAggregations(descendingAggregations);
      for (Map.Entry<PartialPath, List<AggregationDescriptor>> pathAggregationsEntry :
          groupedDescendingAggregations.entrySet()) {
        sourceNodeList.add(
            createAggregationScanNode(
                pathAggregationsEntry.getKey(),
                pathAggregationsEntry.getValue(),
                scanOrder.reverse(),
                null));
      }
    }
    return sourceNodeList;
  }

  private LogicalPlanBuilder convergeAggregationSource(
      List<PlanNode> sourceNodeList,
      AggregationStep curStep,
      Ordering scanOrder,
      GroupByTimeParameter groupByTimeParameter,
      Set<Expression> aggregationExpressions,
      Map<Expression, Set<Expression>> crossGroupByExpressions,
      List<String> tagKeys,
      Map<List<String>, LinkedHashMap<Expression, List<Expression>>>
          tagValuesToGroupedTimeseriesOperands) {
    if (curStep.isOutputPartial()) {
      if (groupByTimeParameter != null && groupByTimeParameter.hasOverlap()) {
        curStep =
            crossGroupByExpressions != null ? AggregationStep.INTERMEDIATE : AggregationStep.FINAL;

        this.root = convergeWithTimeJoin(sourceNodeList, scanOrder);

        this.root =
            createSlidingWindowAggregationNode(
                this.getRoot(), aggregationExpressions, groupByTimeParameter, curStep, scanOrder);

        if (crossGroupByExpressions != null) {
          curStep = AggregationStep.FINAL;
          if (tagKeys != null) {
            this.root =
                createGroupByTagNode(
                    tagKeys,
                    tagValuesToGroupedTimeseriesOperands,
                    crossGroupByExpressions.keySet(),
                    Collections.singletonList(this.getRoot()),
                    curStep,
                    groupByTimeParameter,
                    scanOrder);
          } else {
            this.root =
                createGroupByTLevelNode(
                    Collections.singletonList(this.getRoot()),
                    crossGroupByExpressions,
                    curStep,
                    groupByTimeParameter,
                    scanOrder);
          }
        }
      } else {
        if (tagKeys != null) {
          curStep = AggregationStep.FINAL;
          this.root =
              createGroupByTagNode(
                  tagKeys,
                  tagValuesToGroupedTimeseriesOperands,
                  crossGroupByExpressions.keySet(),
                  sourceNodeList,
                  curStep,
                  groupByTimeParameter,
                  scanOrder);
        } else if (crossGroupByExpressions != null) {
          curStep = AggregationStep.FINAL;
          this.root =
              createGroupByTLevelNode(
                  sourceNodeList,
                  crossGroupByExpressions,
                  curStep,
                  groupByTimeParameter,
                  scanOrder);
        }
      }
    } else {
      this.root = convergeWithTimeJoin(sourceNodeList, scanOrder);
    }

    return this;
  }

  public static void updateTypeProviderByPartialAggregation(
      AggregationDescriptor aggregationDescriptor, TypeProvider typeProvider) {
    if (aggregationDescriptor.getAggregationType() == TAggregationType.UDAF) {
      // Treat UDAF differently
      String partialAggregationNames =
          AggregationUtil.addPartialSuffix(aggregationDescriptor.getAggregationFuncName());
      TSDataType aggregationType = TSDataType.TEXT;
      // Currently UDAF only supports one input series
      String inputExpressionStr =
          aggregationDescriptor.getInputExpressions().get(0).getExpressionString();
      typeProvider.setType(
          String.format("%s(%s)", partialAggregationNames, inputExpressionStr), aggregationType);
    } else {
      List<String> partialAggregationsNames =
          SchemaUtils.splitPartialBuiltinAggregation(aggregationDescriptor.getAggregationType());
      String inputExpressionStr = getInputExpressionString(aggregationDescriptor);
      partialAggregationsNames.forEach(
          x -> setTypeForPartialAggregation(typeProvider, x, inputExpressionStr));
    }
  }

  private static String getInputExpressionString(AggregationDescriptor aggregationDescriptor) {
    // We just process first input Expression of Count_IF
    if (TAggregationType.COUNT_IF.equals(aggregationDescriptor.getAggregationType())) {
      return aggregationDescriptor.getInputExpressions().get(0).getExpressionString();
    } else {
      return aggregationDescriptor.getParametersString();
    }
  }

  private static void setTypeForPartialAggregation(
      TypeProvider typeProvider, String partialAggregationName, String inputExpressionStr) {
    TSDataType aggregationType =
        SchemaUtils.getBuiltinAggregationTypeByFuncName(partialAggregationName);
    typeProvider.setType(
        String.format("%s(%s)", partialAggregationName, inputExpressionStr),
        aggregationType == null ? typeProvider.getType(inputExpressionStr) : aggregationType);
  }

  public static void updateTypeProviderByPartialAggregation(
      CrossSeriesAggregationDescriptor aggregationDescriptor, TypeProvider typeProvider) {
    List<String> partialAggregationsNames =
        SchemaUtils.splitPartialBuiltinAggregation(aggregationDescriptor.getAggregationType());
    if (!AccumulatorFactory.isMultiInputAggregation(aggregationDescriptor.getAggregationType())) {
      if (aggregationDescriptor.getAggregationType() == TAggregationType.UDAF) {
        // Treat UDAF differently
        String partialAggregationNames =
            AggregationUtil.addPartialSuffix(aggregationDescriptor.getAggregationFuncName());
        TSDataType aggregationType = TSDataType.TEXT;
        // Currently UDAF only supports one input series
        String inputExpressionStr =
            aggregationDescriptor.getInputExpressions().get(0).getExpressionString();
        typeProvider.setType(
            String.format("%s(%s)", partialAggregationNames, inputExpressionStr), aggregationType);
      } else {
        PartialPath path =
            ((TimeSeriesOperand) aggregationDescriptor.getOutputExpressions().get(0)).getPath();
        for (String partialAggregationName : partialAggregationsNames) {
          typeProvider.setType(
              String.format("%s(%s)", partialAggregationName, path.getFullPath()),
              SchemaUtils.getSeriesTypeByPath(path, partialAggregationName));
        }
      }
    } else {
      String inputExpressionStr = aggregationDescriptor.getOutputExpressionsAsBuilder().toString();
      partialAggregationsNames.forEach(
          x -> setTypeForPartialAggregation(typeProvider, x, inputExpressionStr));
    }
  }

  protected PlanNode convergeWithTimeJoin(List<PlanNode> sourceNodes, Ordering mergeOrder) {
    PlanNode tmpNode;
    if (sourceNodes.size() == 1) {
      tmpNode = sourceNodes.get(0);
    } else {
      tmpNode =
          new FullOuterTimeJoinNode(context.getQueryId().genPlanNodeId(), mergeOrder, sourceNodes);
    }
    return tmpNode;
  }

  public LogicalPlanBuilder planDeviceView(
      Map<String, PlanNode> deviceNameToSourceNodesMap,
      Set<Expression> deviceViewOutputExpressions,
      Map<String, List<Integer>> deviceToMeasurementIndexesMap,
      Set<Expression> selectExpression,
      QueryStatement queryStatement,
      Analysis analysis) {
    List<String> outputColumnNames =
        deviceViewOutputExpressions.stream()
            .map(Expression::getExpressionString)
            .collect(Collectors.toList());

    List<SortItem> sortItemList = queryStatement.getSortItemList();

    if (sortItemList.isEmpty()) {
      sortItemList = new ArrayList<>();
    }
    if (!queryStatement.isOrderByDevice()) {
      sortItemList.add(new SortItem(OrderByKey.DEVICE, Ordering.ASC));
    }
    if (!queryStatement.isOrderByTime()) {
      sortItemList.add(new SortItem(OrderByKey.TIME, Ordering.ASC));
    }

    OrderByParameter orderByParameter = new OrderByParameter(sortItemList);

    long limitValue =
        queryStatement.hasOffset()
            ? queryStatement.getRowOffset() + queryStatement.getRowLimit()
            : queryStatement.getRowLimit();

    if (canUseTopKNode(queryStatement, limitValue)) {
      TopKNode topKNode =
          new TopKNode(
              context.getQueryId().genPlanNodeId(),
              (int) limitValue,
              orderByParameter,
              outputColumnNames);

      // if value filter exists, need add a LimitNode as the child node of TopKNode
      long valueFilterLimit = queryStatement.hasWhere() ? limitValue : -1;

      // only order by based on time, use TopKNode + SingleDeviceViewNode
      if (queryStatement.isOrderByBasedOnTime() && !queryStatement.hasOrderByExpression()) {
        addSingleDeviceViewNodes(
            topKNode,
            deviceNameToSourceNodesMap,
            outputColumnNames,
            deviceToMeasurementIndexesMap,
            valueFilterLimit);
      } else {
        // has order by expression, use TopKNode + DeviceViewNode
        topKNode.addChild(
            addDeviceViewNode(
                orderByParameter,
                outputColumnNames,
                deviceToMeasurementIndexesMap,
                deviceNameToSourceNodesMap,
                valueFilterLimit));
      }

      analysis.setUseTopKNode();
      this.root = topKNode;
    } else if (canUseMergeSortNode(queryStatement, deviceNameToSourceNodesMap.size())) {
      // use MergeSortNode + SingleDeviceViewNode
      MergeSortNode mergeSortNode =
          new MergeSortNode(
              context.getQueryId().genPlanNodeId(), orderByParameter, outputColumnNames);
      addSingleDeviceViewNodes(
          mergeSortNode,
          deviceNameToSourceNodesMap,
          outputColumnNames,
          deviceToMeasurementIndexesMap,
          -1);
      this.root = mergeSortNode;
    } else {
      // order by based on device, use DeviceViewNode
      this.root =
          addDeviceViewNode(
              orderByParameter,
              outputColumnNames,
              deviceToMeasurementIndexesMap,
              deviceNameToSourceNodesMap,
              -1);
    }

    context.getTypeProvider().setType(DEVICE, TSDataType.TEXT);
    updateTypeProvider(deviceViewOutputExpressions);

    if (queryStatement.needPushDownSort()
        && (selectExpression.size() != deviceViewOutputExpressions.size())) {
      this.root =
          new TransformNode(
              context.getQueryId().genPlanNodeId(),
              root,
              selectExpression.toArray(new Expression[0]),
              queryStatement.isGroupByTime(),
              queryStatement.getSelectComponent().getZoneId(),
              queryStatement.getResultTimeOrder());
    }

    return this;
  }

  private boolean canUseTopKNode(QueryStatement queryStatement, long limitValue) {
    // 1. has LIMIT and LIMIT_VALUE is smaller than 1000000.
    // 2. `order by based on time` or `order by based on expression`.
    // 3. no aggregation or has aggregation but no having.
    // 4. no fill or has fill but not LINEAR fill.
    // when satisfy all cases above will use ToKNode.
    return queryStatement.hasLimit()
        && limitValue <= LIMIT_VALUE_USE_TOP_K
        && queryStatement.hasOrderBy()
        && !queryStatement.isOrderByBasedOnDevice()
        && (!queryStatement.isAggregationQuery()
            || (queryStatement.isAggregationQuery() && !queryStatement.hasHaving()))
        && (!queryStatement.hasFill()
            || !LINEAR.equals(queryStatement.getFillComponent().getFillPolicy()));
  }

  private boolean canUseMergeSortNode(QueryStatement queryStatement, int deviceSize) {
    // 1. `order by based on time` + `no order by expression`.
    // 2. deviceSize is larger than 1.
    // when satisfy all above cases use MergeSortNode + SingleDeviceViewNode.
    return queryStatement.isOrderByBasedOnTime()
        && !queryStatement.hasOrderByExpression()
        && deviceSize > 1;
  }

  private void addSingleDeviceViewNodes(
      MultiChildProcessNode parent,
      Map<String, PlanNode> deviceNameToSourceNodesMap,
      List<String> outputColumnNames,
      Map<String, List<Integer>> deviceToMeasurementIndexesMap,
      long valueFilterLimit) {
    for (Map.Entry<String, PlanNode> entry : deviceNameToSourceNodesMap.entrySet()) {
      String deviceName = entry.getKey();
      PlanNode subPlan = entry.getValue();
      SingleDeviceViewNode singleDeviceViewNode =
          new SingleDeviceViewNode(
              context.getQueryId().genPlanNodeId(),
              outputColumnNames,
              deviceName,
              deviceToMeasurementIndexesMap.get(deviceName));

      // put LIMIT-NODE below of SingleDeviceViewNode if exists value filter
      if (valueFilterLimit > 0) {
        LimitNode limitNode =
            new LimitNode(context.getQueryId().genPlanNodeId(), subPlan, valueFilterLimit);
        singleDeviceViewNode.addChild(limitNode);
      } else {
        singleDeviceViewNode.addChild(subPlan);
      }

      parent.addChild(singleDeviceViewNode);
    }
  }

  private DeviceViewNode addDeviceViewNode(
      OrderByParameter orderByParameter,
      List<String> outputColumnNames,
      Map<String, List<Integer>> deviceToMeasurementIndexesMap,
      Map<String, PlanNode> deviceNameToSourceNodesMap,
      long valueFilterLimit) {
    DeviceViewNode deviceViewNode =
        new DeviceViewNode(
            context.getQueryId().genPlanNodeId(),
            orderByParameter,
            outputColumnNames,
            deviceToMeasurementIndexesMap);

    for (Map.Entry<String, PlanNode> entry : deviceNameToSourceNodesMap.entrySet()) {
      String deviceName = entry.getKey();
      PlanNode subPlan = entry.getValue();
      if (valueFilterLimit > 0) {
        LimitNode limitNode =
            new LimitNode(context.getQueryId().genPlanNodeId(), subPlan, valueFilterLimit);
        deviceViewNode.addChildDeviceNode(deviceName, limitNode);
      } else {
        deviceViewNode.addChildDeviceNode(deviceName, subPlan);
      }
    }
    return deviceViewNode;
  }

  public LogicalPlanBuilder planGroupByLevel(
      Map<Expression, Set<Expression>> groupByLevelExpressions,
      GroupByTimeParameter groupByTimeParameter,
      Ordering scanOrder) {
    if (groupByLevelExpressions == null) {
      return this;
    }

    this.root =
        createGroupByTLevelNode(
            Collections.singletonList(this.getRoot()),
            groupByLevelExpressions,
            AggregationStep.FINAL,
            groupByTimeParameter,
            scanOrder);
    return this;
  }

  public LogicalPlanBuilder planAggregation(
      Set<Expression> aggregationExpressions,
      Expression groupByExpression,
      GroupByTimeParameter groupByTimeParameter,
      GroupByParameter groupByParameter,
      boolean outputEndTime,
      AggregationStep curStep,
      Ordering scanOrder) {
    if (aggregationExpressions == null) {
      return this;
    }

    List<AggregationDescriptor> aggregationDescriptorList =
        constructAggregationDescriptorList(aggregationExpressions, curStep);
    updateTypeProvider(aggregationExpressions);
    if (curStep.isOutputPartial()) {
      aggregationDescriptorList.forEach(
          aggregationDescriptor ->
              updateTypeProviderByPartialAggregation(
                  aggregationDescriptor, context.getTypeProvider()));
    }
    this.root =
        new AggregationNode(
            context.getQueryId().genPlanNodeId(),
            Collections.singletonList(this.getRoot()),
            aggregationDescriptorList,
            groupByTimeParameter,
            groupByParameter,
            groupByExpression,
            outputEndTime,
            scanOrder);
    return this;
  }

  public LogicalPlanBuilder planSlidingWindowAggregation(
      Set<Expression> aggregationExpressions,
      GroupByTimeParameter groupByTimeParameter,
      AggregationStep curStep,
      Ordering scanOrder) {
    if (aggregationExpressions == null) {
      return this;
    }

    this.root =
        createSlidingWindowAggregationNode(
            this.getRoot(), aggregationExpressions, groupByTimeParameter, curStep, scanOrder);
    return this;
  }

  private PlanNode createSlidingWindowAggregationNode(
      PlanNode child,
      Set<Expression> aggregationExpressions,
      GroupByTimeParameter groupByTimeParameter,
      AggregationStep curStep,
      Ordering scanOrder) {
    List<AggregationDescriptor> aggregationDescriptorList =
        constructAggregationDescriptorList(aggregationExpressions, curStep);
    return new SlidingWindowAggregationNode(
        context.getQueryId().genPlanNodeId(),
        child,
        aggregationDescriptorList,
        groupByTimeParameter,
        scanOrder);
  }

  private PlanNode createGroupByTLevelNode(
      List<PlanNode> children,
      Map<Expression, Set<Expression>> groupByLevelExpressions,
      AggregationStep curStep,
      GroupByTimeParameter groupByTimeParameter,
      Ordering scanOrder) {
    List<CrossSeriesAggregationDescriptor> groupByLevelDescriptors = new ArrayList<>();
    for (Map.Entry<Expression, Set<Expression>> entry : groupByLevelExpressions.entrySet()) {
      groupByLevelDescriptors.add(
          new CrossSeriesAggregationDescriptor(
              ((FunctionExpression) entry.getKey()).getFunctionName(),
              curStep,
              entry.getValue().stream()
                  .map(Expression::getExpressions)
                  .flatMap(List::stream)
                  .collect(Collectors.toList()),
              entry.getValue().size(),
              ((FunctionExpression) entry.getKey()).getFunctionAttributes(),
              entry.getKey().getExpressions()));
    }
    updateTypeProvider(groupByLevelExpressions.keySet());
    updateTypeProvider(
        groupByLevelDescriptors.stream()
            .map(CrossSeriesAggregationDescriptor::getOutputExpressions)
            .flatMap(Collection::stream)
            .collect(Collectors.toList()));
    return new GroupByLevelNode(
        context.getQueryId().genPlanNodeId(),
        children,
        groupByLevelDescriptors,
        groupByTimeParameter,
        scanOrder);
  }

  private PlanNode createGroupByTagNode(
      List<String> tagKeys,
      Map<List<String>, LinkedHashMap<Expression, List<Expression>>>
          tagValuesToGroupedTimeseriesOperands,
      Collection<Expression> groupByTagOutputExpressions,
      List<PlanNode> children,
      AggregationStep curStep,
      GroupByTimeParameter groupByTimeParameter,
      Ordering scanOrder) {
    Map<List<String>, List<CrossSeriesAggregationDescriptor>> tagValuesToAggregationDescriptors =
        new HashMap<>();
    for (List<String> tagValues : tagValuesToGroupedTimeseriesOperands.keySet()) {
      LinkedHashMap<Expression, List<Expression>> groupedTimeseriesOperands =
          tagValuesToGroupedTimeseriesOperands.get(tagValues);
      List<CrossSeriesAggregationDescriptor> aggregationDescriptors = new ArrayList<>();

      // Bind an AggregationDescriptor for each GroupByTagOutputExpression
      for (Expression groupByTagOutputExpression : groupByTagOutputExpressions) {
        boolean added = false;
        for (Expression expression : groupedTimeseriesOperands.keySet()) {
          if (expression.equals(groupByTagOutputExpression)) {
            String functionName = ((FunctionExpression) expression).getFunctionName();
            CrossSeriesAggregationDescriptor aggregationDescriptor =
                new CrossSeriesAggregationDescriptor(
                    functionName,
                    curStep,
                    groupedTimeseriesOperands.get(expression),
                    ((FunctionExpression) expression).getFunctionAttributes(),
                    expression.getExpressions());
            aggregationDescriptors.add(aggregationDescriptor);
            added = true;
            break;
          }
        }
        if (!added) {
          aggregationDescriptors.add(null);
        }
      }
      tagValuesToAggregationDescriptors.put(tagValues, aggregationDescriptors);
    }

    updateTypeProvider(groupByTagOutputExpressions);
    updateTypeProviderWithConstantType(tagKeys, TSDataType.TEXT);
    return new GroupByTagNode(
        context.getQueryId().genPlanNodeId(),
        children,
        groupByTimeParameter,
        scanOrder,
        tagKeys,
        tagValuesToAggregationDescriptors,
        groupByTagOutputExpressions.stream()
            .map(Expression::getExpressionString)
            .collect(Collectors.toList()));
  }

  private SeriesAggregationSourceNode createAggregationScanNode(
      PartialPath selectPath,
      List<AggregationDescriptor> aggregationDescriptorList,
      Ordering scanOrder,
      GroupByTimeParameter groupByTimeParameter) {
    if (selectPath instanceof MeasurementPath) { // non-aligned series
      return new SeriesAggregationScanNode(
          context.getQueryId().genPlanNodeId(),
          (MeasurementPath) selectPath,
          aggregationDescriptorList,
          scanOrder,
          groupByTimeParameter);
    } else if (selectPath instanceof AlignedPath) { // aligned series
      return new AlignedSeriesAggregationScanNode(
          context.getQueryId().genPlanNodeId(),
          (AlignedPath) selectPath,
          aggregationDescriptorList,
          scanOrder,
          groupByTimeParameter);
    } else {
      throw new IllegalArgumentException("unexpected path type");
    }
  }

  private List<AggregationDescriptor> constructAggregationDescriptorList(
      Set<Expression> aggregationExpressions, AggregationStep curStep) {
    return aggregationExpressions.stream()
        .map(
            expression -> {
              Validate.isTrue(expression instanceof FunctionExpression);
              return new AggregationDescriptor(
                  ((FunctionExpression) expression).getFunctionName(),
                  curStep,
                  expression.getExpressions(),
                  ((FunctionExpression) expression).getFunctionAttributes());
            })
        .collect(Collectors.toList());
  }

  public LogicalPlanBuilder planFilterAndTransform(
      Expression filterExpression,
      Set<Expression> selectExpressions,
      boolean isGroupByTime,
      ZoneId zoneId,
      Ordering scanOrder) {
    if (filterExpression == null || selectExpressions.isEmpty()) {
      return this;
    }

    this.root =
        new FilterNode(
            context.getQueryId().genPlanNodeId(),
            this.getRoot(),
            selectExpressions.toArray(new Expression[0]),
            filterExpression,
            isGroupByTime,
            zoneId,
            scanOrder);
    updateTypeProvider(selectExpressions);
    return this;
  }

  public LogicalPlanBuilder planTransform(
      Set<Expression> selectExpressions, boolean isGroupByTime, ZoneId zoneId, Ordering scanOrder) {
    boolean needTransform = false;
    for (Expression expression : selectExpressions) {
      if (ExpressionAnalyzer.checkIsNeedTransform(expression)) {
        needTransform = true;
        break;
      }
    }
    if (!needTransform) {
      return this;
    }

    this.root =
        new TransformNode(
            context.getQueryId().genPlanNodeId(),
            this.getRoot(),
            selectExpressions.toArray(new Expression[0]),
            isGroupByTime,
            zoneId,
            scanOrder);
    updateTypeProvider(selectExpressions);
    return this;
  }

  public LogicalPlanBuilder planFill(FillDescriptor fillDescriptor, Ordering scanOrder) {
    if (fillDescriptor == null) {
      return this;
    }

    this.root =
        new FillNode(
            context.getQueryId().genPlanNodeId(), this.getRoot(), fillDescriptor, scanOrder);
    return this;
  }

  public LogicalPlanBuilder planLimit(long rowLimit) {
    if (rowLimit == 0) {
      return this;
    }

    this.root = new LimitNode(context.getQueryId().genPlanNodeId(), this.getRoot(), rowLimit);
    return this;
  }

  public LogicalPlanBuilder planOffset(long rowOffset) {
    if (rowOffset == 0) {
      return this;
    }

    this.root = new OffsetNode(context.getQueryId().genPlanNodeId(), this.getRoot(), rowOffset);
    return this;
  }

  public LogicalPlanBuilder planHavingAndTransform(
      Expression havingExpression,
      Set<Expression> selectExpressions,
      Set<Expression> orderByExpression,
      boolean isGroupByTime,
      ZoneId zoneId,
      Ordering scanOrder) {

    Set<Expression> outputExpressions = new HashSet<>(selectExpressions);
    if (orderByExpression != null) {
      outputExpressions.addAll(orderByExpression);
    }

    if (havingExpression != null) {
      return planFilterAndTransform(
          havingExpression, outputExpressions, isGroupByTime, zoneId, scanOrder);
    } else {
      return planTransform(outputExpressions, isGroupByTime, zoneId, scanOrder);
    }
  }

  public LogicalPlanBuilder planWhereAndSourceTransform(
      Expression whereExpression,
      Set<Expression> sourceTransformExpressions,
      boolean isGroupByTime,
      ZoneId zoneId,
      Ordering scanOrder) {
    if (whereExpression != null) {
      return planFilterAndTransform(
          whereExpression, sourceTransformExpressions, isGroupByTime, zoneId, scanOrder);
    } else {
      return planTransform(sourceTransformExpressions, isGroupByTime, zoneId, scanOrder);
    }
  }

  public LogicalPlanBuilder planDeviceViewInto(
      DeviceViewIntoPathDescriptor deviceViewIntoPathDescriptor) {
    if (deviceViewIntoPathDescriptor == null) {
      return this;
    }

    ColumnHeaderConstant.selectIntoAlignByDeviceColumnHeaders.forEach(
        columnHeader ->
            updateTypeProviderWithConstantType(
                columnHeader.getColumnName(), columnHeader.getColumnType()));
    this.root =
        new DeviceViewIntoNode(
            context.getQueryId().genPlanNodeId(), this.getRoot(), deviceViewIntoPathDescriptor);
    return this;
  }

  public LogicalPlanBuilder planInto(IntoPathDescriptor intoPathDescriptor) {
    if (intoPathDescriptor == null) {
      return this;
    }

    ColumnHeaderConstant.selectIntoColumnHeaders.forEach(
        columnHeader -> {
          updateTypeProviderWithConstantType(
              columnHeader.getColumnName(), columnHeader.getColumnType());
        });
    this.root =
        new IntoNode(context.getQueryId().genPlanNodeId(), this.getRoot(), intoPathDescriptor);
    return this;
  }

  /** Meta Query* */
  public LogicalPlanBuilder planTimeSeriesSchemaSource(
      PartialPath pathPattern,
      SchemaFilter schemaFilter,
      long limit,
      long offset,
      boolean orderByHeat,
      boolean prefixPath,
      Map<Integer, Template> templateMap,
      PathPatternTree scope) {
    this.root =
        new TimeSeriesSchemaScanNode(
            context.getQueryId().genPlanNodeId(),
            pathPattern,
            schemaFilter,
            limit,
            offset,
            orderByHeat,
            prefixPath,
            templateMap,
            scope);
    return this;
  }

  public LogicalPlanBuilder planDeviceSchemaSource(
      PartialPath pathPattern,
      long limit,
      long offset,
      boolean prefixPath,
      boolean hasSgCol,
      SchemaFilter schemaFilter,
      PathPatternTree scope) {
    this.root =
        new DevicesSchemaScanNode(
            context.getQueryId().genPlanNodeId(),
            pathPattern,
            limit,
            offset,
            prefixPath,
            hasSgCol,
            schemaFilter,
            scope);
    return this;
  }

  public LogicalPlanBuilder planSchemaQueryMerge(boolean orderByHeat) {
    SchemaQueryMergeNode schemaMergeNode =
        new SchemaQueryMergeNode(context.getQueryId().genPlanNodeId(), orderByHeat);
    schemaMergeNode.addChild(this.getRoot());
    this.root = schemaMergeNode;
    return this;
  }

  public LogicalPlanBuilder planSchemaQueryOrderByHeat(PlanNode lastPlanNode) {
    SchemaQueryOrderByHeatNode node =
        new SchemaQueryOrderByHeatNode(context.getQueryId().genPlanNodeId());
    node.addChild(this.getRoot());
    node.addChild(lastPlanNode);
    this.root = node;
    return this;
  }

  public LogicalPlanBuilder planSchemaFetchMerge(List<String> storageGroupList) {
    this.root = new SchemaFetchMergeNode(context.getQueryId().genPlanNodeId(), storageGroupList);
    return this;
  }

  @SuppressWarnings({"checkstyle:Indentation", "checkstyle:CommentsIndentation"})
  public LogicalPlanBuilder planSchemaFetchSource(
      List<String> storageGroupList,
      PathPatternTree patternTree,
      Map<Integer, Template> templateMap,
      boolean withTags,
      boolean withTemplate) {
    PartialPath storageGroupPath;
    for (String storageGroup : storageGroupList) {
      try {
        storageGroupPath = new PartialPath(storageGroup);
        PathPatternTree overlappedPatternTree = new PathPatternTree();
        for (PartialPath pathPattern :
            patternTree.getOverlappedPathPatterns(
                storageGroupPath.concatNode(MULTI_LEVEL_PATH_WILDCARD))) {
          // pathPattern has been deduplicated, no need to deduplicate again
          overlappedPatternTree.appendFullPath(pathPattern);
        }
        this.root.addChild(
            new SchemaFetchScanNode(
                context.getQueryId().genPlanNodeId(),
                storageGroupPath,
                overlappedPatternTree,
                templateMap,
                withTags,
                withTemplate));
      } catch (IllegalPathException e) {
        // definitely won't happen
        throw new RuntimeException(e);
      }
    }
    return this;
  }

  public LogicalPlanBuilder planCountMerge() {
    CountSchemaMergeNode countMergeNode =
        new CountSchemaMergeNode(context.getQueryId().genPlanNodeId());
    countMergeNode.addChild(this.getRoot());
    this.root = countMergeNode;
    return this;
  }

  public LogicalPlanBuilder planDevicesCountSource(
      PartialPath partialPath, boolean prefixPath, PathPatternTree scope) {
    this.root =
        new DevicesCountNode(context.getQueryId().genPlanNodeId(), partialPath, prefixPath, scope);
    return this;
  }

  public LogicalPlanBuilder planTimeSeriesCountSource(
      PartialPath partialPath,
      boolean prefixPath,
      SchemaFilter schemaFilter,
      Map<Integer, Template> templateMap,
      PathPatternTree scope) {
    this.root =
        new TimeSeriesCountNode(
            context.getQueryId().genPlanNodeId(),
            partialPath,
            prefixPath,
            schemaFilter,
            templateMap,
            scope);
    return this;
  }

  public LogicalPlanBuilder planLevelTimeSeriesCountSource(
      PartialPath partialPath,
      boolean prefixPath,
      int level,
      SchemaFilter schemaFilter,
      Map<Integer, Template> templateMap,
      PathPatternTree scope) {
    this.root =
        new LevelTimeSeriesCountNode(
            context.getQueryId().genPlanNodeId(),
            partialPath,
            prefixPath,
            level,
            schemaFilter,
            templateMap,
            scope);
    return this;
  }

  public LogicalPlanBuilder planNodePathsSchemaSource(
      PartialPath partialPath, Integer level, PathPatternTree scope) {
    this.root =
        new NodePathsSchemaScanNode(
            context.getQueryId().genPlanNodeId(), partialPath, level, scope);
    return this;
  }

  public LogicalPlanBuilder planNodePathsConvert() {
    NodePathsConvertNode nodePathsConvertNode =
        new NodePathsConvertNode(context.getQueryId().genPlanNodeId());
    nodePathsConvertNode.addChild(this.getRoot());
    this.root = nodePathsConvertNode;
    return this;
  }

  public LogicalPlanBuilder planNodePathsCount() {
    NodePathsCountNode nodePathsCountNode =
        new NodePathsCountNode(context.getQueryId().genPlanNodeId());
    nodePathsCountNode.addChild(this.getRoot());
    this.root = nodePathsCountNode;
    return this;
  }

  public LogicalPlanBuilder planNodeManagementMemoryMerge(Set<TSchemaNode> data) {
    NodeManagementMemoryMergeNode memorySourceNode =
        new NodeManagementMemoryMergeNode(context.getQueryId().genPlanNodeId(), data);
    memorySourceNode.addChild(this.getRoot());
    this.root = memorySourceNode;
    return this;
  }

  public LogicalPlanBuilder planPathsUsingTemplateSource(
      List<PartialPath> pathPatternList, int templateId, PathPatternTree scope) {
    this.root =
        new PathsUsingTemplateScanNode(
            context.getQueryId().genPlanNodeId(), pathPatternList, templateId, scope);
    return this;
  }

  public LogicalPlanBuilder planLogicalViewSchemaSource(
      PartialPath pathPattern,
      SchemaFilter schemaFilter,
      long limit,
      long offset,
      PathPatternTree scope) {
    this.root =
        new LogicalViewSchemaScanNode(
            context.getQueryId().genPlanNodeId(), pathPattern, schemaFilter, limit, offset, scope);
    return this;
  }

  private LogicalPlanBuilder planSort(OrderByParameter orderByParameter) {
    if (orderByParameter.isEmpty()) {
      return this;
    }
    this.root = new SortNode(context.getQueryId().genPlanNodeId(), root, orderByParameter);
    return this;
  }

  public LogicalPlanBuilder planShowQueries(Analysis analysis, ShowQueriesStatement statement) {
    List<TDataNodeLocation> dataNodeLocations = analysis.getRunningDataNodeLocations();
    if (dataNodeLocations.size() == 1) {
      this.root =
          planSingleShowQueries(dataNodeLocations.get(0))
              .planFilterAndTransform(
                  analysis.getWhereExpression(),
                  analysis.getSourceExpressions(),
                  false,
                  statement.getZoneId(),
                  Ordering.ASC)
              .planSort(analysis.getMergeOrderParameter())
              .getRoot();
    } else {
      List<String> outputColumns = new ArrayList<>();
      MergeSortNode mergeSortNode =
          new MergeSortNode(
              context.getQueryId().genPlanNodeId(),
              analysis.getMergeOrderParameter(),
              outputColumns);

      dataNodeLocations.forEach(
          dataNodeLocation ->
              mergeSortNode.addChild(
                  this.planSingleShowQueries(dataNodeLocation)
                      .planFilterAndTransform(
                          analysis.getWhereExpression(),
                          analysis.getSourceExpressions(),
                          false,
                          statement.getZoneId(),
                          Ordering.ASC)
                      .planSort(analysis.getMergeOrderParameter())
                      .getRoot()));
      outputColumns.addAll(mergeSortNode.getChildren().get(0).getOutputColumnNames());
      this.root = mergeSortNode;
    }

    ColumnHeaderConstant.showQueriesColumnHeaders.forEach(
        columnHeader ->
            context
                .getTypeProvider()
                .setType(columnHeader.getColumnName(), columnHeader.getColumnType()));
    return this;
  }

  private LogicalPlanBuilder planSingleShowQueries(TDataNodeLocation dataNodeLocation) {
    this.root = new ShowQueriesNode(context.getQueryId().genPlanNodeId(), dataNodeLocation);
    return this;
  }

  public LogicalPlanBuilder planOrderBy(List<SortItem> sortItemList) {
    if (sortItemList.isEmpty()) {
      return this;
    }

    this.root =
        new SortNode(
            context.getQueryId().genPlanNodeId(), root, new OrderByParameter(sortItemList));
    return this;
  }

  public LogicalPlanBuilder planOrderBy(
      Set<Expression> orderByExpressions, List<SortItem> sortItemList) {

    updateTypeProvider(orderByExpressions);
    OrderByParameter orderByParameter = new OrderByParameter(sortItemList);
    if (orderByParameter.isEmpty()) {
      return this;
    }
    this.root = new SortNode(context.getQueryId().genPlanNodeId(), root, orderByParameter);
    return this;
  }

  public LogicalPlanBuilder planOrderBy(QueryStatement queryStatement, Analysis analysis) {
    // only the order by clause having expression needs a sortNode
    if (!queryStatement.hasOrderByExpression()) {
      return this;
    }

    // TopKNode is already sorted, does not need sort
    if (root instanceof TopKNode) {
      return this;
    }

    Set<Expression> orderByExpressions = analysis.getOrderByExpressions();
    updateTypeProvider(orderByExpressions);
    OrderByParameter orderByParameter = new OrderByParameter(queryStatement.getSortItemList());
    if (orderByParameter.isEmpty()) {
      return this;
    }

    this.root = new SortNode(context.getQueryId().genPlanNodeId(), root, orderByParameter);

    Set<Expression> selectExpression = analysis.getSelectExpressions();
    if (root.getOutputColumnNames().size() != selectExpression.size()) {
      this.root =
          new TransformNode(
              context.getQueryId().genPlanNodeId(),
              root,
              selectExpression.toArray(new Expression[0]),
              queryStatement.isGroupByTime(),
              queryStatement.getSelectComponent().getZoneId(),
              queryStatement.getResultTimeOrder());
    }

    return this;
  }

  public LogicalPlanBuilder planEndTimeColumnInject(
      GroupByTimeParameter groupByTimeParameter, boolean ascending) {
    this.root =
        new ColumnInjectNode(
            context.getQueryId().genPlanNodeId(),
            this.getRoot(),
            0,
            new SlidingTimeColumnGeneratorParameter(groupByTimeParameter, ascending));
    return this;
  }
}
