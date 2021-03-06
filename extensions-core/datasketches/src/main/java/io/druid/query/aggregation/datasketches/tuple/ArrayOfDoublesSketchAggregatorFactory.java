/*
 * Licensed to Metamarkets Group Inc. (Metamarkets) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Metamarkets licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.druid.query.aggregation.datasketches.tuple;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;

import io.druid.java.util.common.IAE;
import io.druid.query.aggregation.Aggregator;
import io.druid.query.aggregation.AggregatorFactory;
import io.druid.query.aggregation.AggregatorUtil;
import io.druid.query.aggregation.BufferAggregator;
import io.druid.query.cache.CacheKeyBuilder;
import io.druid.query.dimension.DefaultDimensionSpec;
import io.druid.segment.BaseDoubleColumnValueSelector;
import io.druid.segment.BaseObjectColumnValueSelector;
import io.druid.segment.ColumnSelectorFactory;
import io.druid.segment.DimensionSelector;
import io.druid.segment.DimensionSelectorUtils;
import io.druid.segment.NilColumnValueSelector;

import com.yahoo.sketches.Util;
import com.yahoo.sketches.tuple.ArrayOfDoublesSketch;
import com.yahoo.sketches.tuple.ArrayOfDoublesUnion;
import com.yahoo.sketches.tuple.ArrayOfDoublesSetOperationBuilder;

public class ArrayOfDoublesSketchAggregatorFactory extends AggregatorFactory
{

  public static final Comparator<ArrayOfDoublesSketch> COMPARATOR =
      Comparator.nullsFirst(Comparator.comparingDouble(ArrayOfDoublesSketch::getEstimate));

  private final String name;
  private final String fieldName;
  private final int nominalEntries;
  private final int numberOfValues;
  // if specified indicates building sketched from raw data, and also implies the number of values
  @Nullable private final List<String> metricColumns; 

  @JsonCreator
  public ArrayOfDoublesSketchAggregatorFactory(
      @JsonProperty("name") final String name,
      @JsonProperty("fieldName") final String fieldName,
      @JsonProperty("nominalEntries") @Nullable final Integer nominalEntries,
      @JsonProperty("metricColumns") @Nullable final List<String> metricColumns,
      @JsonProperty("numberOfValues") @Nullable final Integer numberOfValues
  )
  {
    this.name = Preconditions.checkNotNull(name, "Must have a valid, non-null aggregator name");
    this.fieldName = Preconditions.checkNotNull(fieldName, "Must have a valid, non-null fieldName");
    this.nominalEntries = nominalEntries == null ? Util.DEFAULT_NOMINAL_ENTRIES : nominalEntries;
    Util.checkIfPowerOf2(this.nominalEntries, "nominalEntries");
    this.metricColumns = metricColumns;
    this.numberOfValues = numberOfValues == null ? (metricColumns == null ? 1 : metricColumns.size()) : numberOfValues;
    if (metricColumns != null && metricColumns.size() != this.numberOfValues) {
      throw new IAE(
          "Number of metricColumns [%d] must agree with numValues [%d]",
          metricColumns.size(),
          this.numberOfValues
      );
    }
  }

  @Override
  public Aggregator factorize(final ColumnSelectorFactory metricFactory)
  {
    if (metricColumns == null) { // input is sketches, use merge aggregator
      final BaseObjectColumnValueSelector<ArrayOfDoublesSketch> selector = metricFactory
          .makeColumnValueSelector(fieldName);
      if (selector instanceof NilColumnValueSelector) {
        return new ArrayOfDoublesSketchNoOpAggregator(numberOfValues);
      }
      return new ArrayOfDoublesSketchMergeAggregator(selector, nominalEntries, numberOfValues);
    }
    // input is raw data (key and array of values), use build aggregator
    final DimensionSelector keySelector = metricFactory
        .makeDimensionSelector(new DefaultDimensionSpec(fieldName, fieldName));
    if (DimensionSelectorUtils.isNilSelector(keySelector)) {
      return new ArrayOfDoublesSketchNoOpAggregator(numberOfValues);
    }
    final List<BaseDoubleColumnValueSelector> valueSelectors = new ArrayList<>();
    for (final String column : metricColumns) {
      final BaseDoubleColumnValueSelector valueSelector = metricFactory.makeColumnValueSelector(column);
      valueSelectors.add(valueSelector);
    }
    return new ArrayOfDoublesSketchBuildAggregator(keySelector, valueSelectors, nominalEntries);
  }

  @Override
  public BufferAggregator factorizeBuffered(final ColumnSelectorFactory metricFactory)
  {
    if (metricColumns == null) { // input is sketches, use merge aggregator
      final BaseObjectColumnValueSelector<ArrayOfDoublesSketch> selector = metricFactory
          .makeColumnValueSelector(fieldName);
      if (selector instanceof NilColumnValueSelector) {
        return new ArrayOfDoublesSketchNoOpBufferAggregator(numberOfValues);
      }
      return new ArrayOfDoublesSketchMergeBufferAggregator(
          selector,
          nominalEntries,
          numberOfValues,
          getMaxIntermediateSize()
      );
    }
    // input is raw data (key and array of values), use build aggregator
    final DimensionSelector keySelector = metricFactory
        .makeDimensionSelector(new DefaultDimensionSpec(fieldName, fieldName));
    if (DimensionSelectorUtils.isNilSelector(keySelector)) {
      return new ArrayOfDoublesSketchNoOpBufferAggregator(numberOfValues);
    }
    final List<BaseDoubleColumnValueSelector> valueSelectors = new ArrayList<>();
    for (final String column : metricColumns) {
      final BaseDoubleColumnValueSelector valueSelector = metricFactory.makeColumnValueSelector(column);
      valueSelectors.add(valueSelector);
    }
    return new ArrayOfDoublesSketchBuildBufferAggregator(
        keySelector,
        valueSelectors,
        nominalEntries,
        getMaxIntermediateSize()
    );
  }

  @Override
  public Object deserialize(final Object object)
  {
    return ArrayOfDoublesSketchOperations.deserialize(object);
  }

  @Override
  public Comparator<ArrayOfDoublesSketch> getComparator()
  {
    return COMPARATOR;
  }

  @Override
  public Object combine(@Nullable final Object lhs, @Nullable final Object rhs)
  {
    final ArrayOfDoublesUnion union = new ArrayOfDoublesSetOperationBuilder().setNominalEntries(nominalEntries)
        .setNumberOfValues(numberOfValues).buildUnion();
    if (lhs != null) {
      union.update((ArrayOfDoublesSketch) lhs);
    }
    if (rhs != null) {
      union.update((ArrayOfDoublesSketch) rhs);
    }
    return union.getResult();
  }

  @Override
  @JsonProperty
  public String getName()
  {
    return name;
  }

  @JsonProperty
  public String getFieldName()
  {
    return fieldName;
  }

  @JsonProperty
  public int getNominalEntries()
  {
    return nominalEntries;
  }

  @JsonProperty
  public List<String> getMetricColumns()
  {
    return metricColumns;
  }

  @JsonProperty
  public int getNumberOfValues()
  {
    return numberOfValues;
  }

  @Override
  public List<String> requiredFields()
  {
    return Collections.singletonList(fieldName);
  }

  @Override
  public byte[] getCacheKey()
  {
    final CacheKeyBuilder builder = new CacheKeyBuilder(AggregatorUtil.ARRAY_OF_DOUBLES_SKETCH_CACHE_TYPE_ID)
        .appendString(name)
        .appendString(fieldName)
        .appendInt(nominalEntries)
        .appendInt(numberOfValues);
    if (metricColumns != null) {
      builder.appendStrings(metricColumns);
    }
    return builder.build();
  }

  @Override
  public int getMaxIntermediateSize()
  {
    return ArrayOfDoublesUnion.getMaxBytes(nominalEntries, numberOfValues);
  }

  @Override
  public List<AggregatorFactory> getRequiredColumns()
  {
    return Collections.<AggregatorFactory>singletonList(
      new ArrayOfDoublesSketchAggregatorFactory(
          fieldName,
          fieldName,
          nominalEntries,
          metricColumns,
          numberOfValues
      )
    );
  }

  @Override
  public AggregatorFactory getCombiningFactory()
  {
    return new ArrayOfDoublesSketchAggregatorFactory(name, name, nominalEntries, null, numberOfValues);
  }

  @Override
  public Object finalizeComputation(final Object object)
  {
    return ((ArrayOfDoublesSketch) object).getEstimate();
  }

  @Override
  public String getTypeName()
  {
    if (metricColumns == null) {
      return ArrayOfDoublesSketchModule.ARRAY_OF_DOUBLES_SKETCH_MERGE_AGG;
    }
    return ArrayOfDoublesSketchModule.ARRAY_OF_DOUBLES_SKETCH_BUILD_AGG;
  }

  @Override
  public boolean equals(final Object o)
  {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ArrayOfDoublesSketchAggregatorFactory)) {
      return false;
    }
    final ArrayOfDoublesSketchAggregatorFactory that = (ArrayOfDoublesSketchAggregatorFactory) o;
    if (!name.equals(that.name)) {
      return false;
    }
    if (!fieldName.equals(that.fieldName)) {
      return false;
    }
    if (nominalEntries != that.nominalEntries) {
      return false;
    }
    if (!Objects.equals(metricColumns, that.metricColumns)) {
      return false;
    }
    return numberOfValues != that.numberOfValues;
  }

  @Override
  public int hashCode()
  {
    return Objects.hash(name, fieldName, nominalEntries, metricColumns, numberOfValues);
  }

  @Override
  public String toString()
  {
    return this.getClass().getSimpleName() + "{"
        + "fieldName=" + fieldName
        + ", name=" + name
        + ", nominalEntries=" + nominalEntries
        + ", metricColumns=" + metricColumns
        + ", numberOfValues=" + numberOfValues
        + "}";
  }

}
