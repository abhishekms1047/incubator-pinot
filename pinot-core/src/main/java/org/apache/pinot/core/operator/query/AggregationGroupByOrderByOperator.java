/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.core.operator.query;

import org.apache.pinot.common.request.GroupBy;
import org.apache.pinot.common.request.transform.TransformExpressionTree;
import org.apache.pinot.common.utils.DataSchema;
import org.apache.pinot.core.operator.BaseOperator;
import org.apache.pinot.core.operator.ExecutionStatistics;
import org.apache.pinot.core.operator.blocks.IntermediateResultsBlock;
import org.apache.pinot.core.operator.blocks.TransformBlock;
import org.apache.pinot.core.operator.transform.TransformOperator;
import org.apache.pinot.core.query.aggregation.AggregationFunctionContext;
import org.apache.pinot.core.query.aggregation.groupby.DefaultGroupByExecutor;
import org.apache.pinot.core.query.aggregation.groupby.GroupByExecutor;
import org.apache.pinot.core.startree.executor.StarTreeGroupByExecutor;


/**
 * The <code>AggregationOperator</code> class provides the operator for aggregation group-by query on a single segment.
 */
public class AggregationGroupByOrderByOperator extends BaseOperator<IntermediateResultsBlock> {
  private static final String OPERATOR_NAME = "AggregationGroupByOrderByOperator";

  private final DataSchema _dataSchema;

  private final AggregationFunctionContext[] _functionContexts;
  private final GroupBy _groupBy;
  private final int _maxInitialResultHolderCapacity;
  private final int _numGroupsLimit;
  private final TransformOperator _transformOperator;
  private final long _numTotalDocs;
  private final boolean _useStarTree;

  private int _numDocsScanned;

  public AggregationGroupByOrderByOperator(AggregationFunctionContext[] functionContexts, GroupBy groupBy,
      int maxInitialResultHolderCapacity, int numGroupsLimit, TransformOperator transformOperator, long numTotalDocs,
      boolean useStarTree) {
    _functionContexts = functionContexts;
    _groupBy = groupBy;
    _maxInitialResultHolderCapacity = maxInitialResultHolderCapacity;
    _numGroupsLimit = numGroupsLimit;
    _transformOperator = transformOperator;
    _numTotalDocs = numTotalDocs;
    _useStarTree = useStarTree;

    // NOTE: The indexedTable expects that the the data schema will have group by columns before aggregation columns
    int numColumns = groupBy.getExpressionsSize() + _functionContexts.length;
    String[] columnNames = new String[numColumns];
    DataSchema.ColumnDataType[] columnDataTypes = new DataSchema.ColumnDataType[numColumns];

    // extract column names and data types for group by keys
    int index = 0;
    for (String groupByColumn : groupBy.getExpressions()) {
      columnNames[index] = groupByColumn;
      TransformExpressionTree expression = TransformExpressionTree.compileToExpressionTree(groupByColumn);
      columnDataTypes[index] =
          DataSchema.ColumnDataType.fromDataTypeSV(_transformOperator.getResultMetadata(expression).getDataType());
      index++;
    }

    // extract column names and data types for aggregations
    for (AggregationFunctionContext functionContext : functionContexts) {
      columnNames[index] = functionContext.getResultColumnName();
      columnDataTypes[index] = functionContext.getAggregationFunction().getIntermediateResultColumnType();
      index++;
    }

    // TODO: We need to support putting order by columns in the data schema.
    //  It is possible that the order by column is not one of the group by or aggregation columns

    _dataSchema = new DataSchema(columnNames, columnDataTypes);
  }

  @Override
  protected IntermediateResultsBlock getNextBlock() {
    // Perform aggregation group-by on all the blocks
    GroupByExecutor groupByExecutor;
    if (_useStarTree) {
      groupByExecutor =
          new StarTreeGroupByExecutor(_functionContexts, _groupBy, _maxInitialResultHolderCapacity, _numGroupsLimit,
              _transformOperator);
    } else {
      groupByExecutor =
          new DefaultGroupByExecutor(_functionContexts, _groupBy, _maxInitialResultHolderCapacity, _numGroupsLimit,
              _transformOperator);
    }
    TransformBlock transformBlock;
    while ((transformBlock = _transformOperator.nextBlock()) != null) {
      _numDocsScanned += transformBlock.getNumDocs();
      groupByExecutor.process(transformBlock);
    }

    // Build intermediate result block based on aggregation group-by result from the executor
    return new IntermediateResultsBlock(_functionContexts, groupByExecutor.getResult(), _dataSchema);
  }

  @Override
  public String getOperatorName() {
    return OPERATOR_NAME;
  }

  @Override
  public ExecutionStatistics getExecutionStatistics() {
    long numEntriesScannedInFilter = _transformOperator.getExecutionStatistics().getNumEntriesScannedInFilter();
    long numEntriesScannedPostFilter = (long) _numDocsScanned * _transformOperator.getNumColumnsProjected();
    return new ExecutionStatistics(_numDocsScanned, numEntriesScannedInFilter, numEntriesScannedPostFilter,
        _numTotalDocs);
  }
}
