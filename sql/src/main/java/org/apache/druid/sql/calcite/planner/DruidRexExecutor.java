/*
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

package org.apache.druid.sql.calcite.planner;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexExecutor;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.IAE;
import org.apache.druid.math.expr.Expr;
import org.apache.druid.math.expr.ExprEval;
import org.apache.druid.math.expr.ExprType;
import org.apache.druid.math.expr.InputBindings;
import org.apache.druid.math.expr.Parser;
import org.apache.druid.segment.column.RowSignature;
import org.apache.druid.sql.calcite.expression.DruidExpression;
import org.apache.druid.sql.calcite.expression.Expressions;
import org.apache.druid.sql.calcite.table.RowSignatures;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A Calcite {@code RexExecutor} that reduces Calcite expressions by evaluating them using Druid's own built-in
 * expressions. This ensures that constant reduction is done in a manner consistent with the query runtime.
 */
public class DruidRexExecutor implements RexExecutor
{
  private static final RowSignature EMPTY_ROW_SIGNATURE = RowSignature.builder().build();

  private final PlannerContext plannerContext;

  public DruidRexExecutor(final PlannerContext plannerContext)
  {
    this.plannerContext = plannerContext;
  }

  @Override
  public void reduce(
      final RexBuilder rexBuilder,
      final List<RexNode> constExps,
      final List<RexNode> reducedValues
  )
  {
    for (RexNode constExp : constExps) {
      final DruidExpression druidExpression = Expressions.toDruidExpression(
          plannerContext,
          EMPTY_ROW_SIGNATURE,
          constExp
      );

      if (druidExpression == null) {
        reducedValues.add(constExp);
      } else {
        final SqlTypeName sqlTypeName = constExp.getType().getSqlTypeName();
        final Expr expr = Parser.parse(druidExpression.getExpression(), plannerContext.getExprMacroTable());

        final ExprEval exprResult = expr.eval(
            InputBindings.forFunction(
                name -> {
                  // Sanity check. Bindings should not be used for a constant expression.
                  throw new UnsupportedOperationException();
                }
            )
        );

        final RexNode literal;

        if (sqlTypeName == SqlTypeName.BOOLEAN) {
          literal = rexBuilder.makeLiteral(exprResult.asBoolean(), constExp.getType(), true);
        } else if (sqlTypeName == SqlTypeName.DATE) {
          // It is possible for an expression to have a non-null String value but it can return null when parsed
          // as a primitive long/float/double.
          // ExprEval.isNumericNull checks whether the parsed primitive value is null or not.
          if (!constExp.getType().isNullable() && exprResult.isNumericNull()) {
            throw new IAE("Illegal DATE constant: %s", constExp);
          }

          literal = rexBuilder.makeDateLiteral(
              Calcites.jodaToCalciteDateString(
                  DateTimes.utc(exprResult.asLong()),
                  plannerContext.getTimeZone()
              )
          );
        } else if (sqlTypeName == SqlTypeName.TIMESTAMP) {
          // It is possible for an expression to have a non-null String value but it can return null when parsed
          // as a primitive long/float/double.
          // ExprEval.isNumericNull checks whether the parsed primitive value is null or not.
          if (!constExp.getType().isNullable() && exprResult.isNumericNull()) {
            throw new IAE("Illegal TIMESTAMP constant: %s", constExp);
          }

          literal = rexBuilder.makeTimestampLiteral(
              Calcites.jodaToCalciteTimestampString(
                  DateTimes.utc(exprResult.asLong()),
                  plannerContext.getTimeZone()
              ),
              RelDataType.PRECISION_NOT_SPECIFIED
          );
        } else if (SqlTypeName.NUMERIC_TYPES.contains(sqlTypeName)) {
          final BigDecimal bigDecimal;

          if (exprResult.isNumericNull()) {
            literal = rexBuilder.makeNullLiteral(constExp.getType());
          } else {
            if (exprResult.type().is(ExprType.LONG)) {
              bigDecimal = BigDecimal.valueOf(exprResult.asLong());

            } else {
              // if exprResult evaluates to Nan or infinity, this will throw a NumberFormatException.
              // If you find yourself in such a position, consider casting the literal to a BIGINT so that
              // the query can execute.
              double exprResultDouble = exprResult.asDouble();
              if (Double.isNaN(exprResultDouble) || Double.isInfinite(exprResultDouble)) {
                String expression = druidExpression.getExpression();
                throw new IAE("'%s' evaluates to '%s' that is not supported in SQL. You can either cast the expression as bigint ('cast(%s as bigint)') or char ('cast(%s as char)') or change the expression itself",
                    expression,
                    Double.toString(exprResultDouble),
                    expression,
                    expression);
              }
              bigDecimal = BigDecimal.valueOf(exprResult.asDouble());
            }
            literal = rexBuilder.makeLiteral(bigDecimal, constExp.getType(), true);
          }
        } else if (sqlTypeName == SqlTypeName.ARRAY) {
          assert exprResult.isArray();
          if (SqlTypeName.NUMERIC_TYPES.contains(constExp.getType().getComponentType().getSqlTypeName())) {
            if (exprResult.type().getElementType().is(ExprType.LONG)) {
              List<BigDecimal> resultAsBigDecimalList = Arrays.stream(exprResult.asLongArray())
                                                              .map(BigDecimal::valueOf)
                                                              .collect(Collectors.toList());
              literal = rexBuilder.makeLiteral(resultAsBigDecimalList, constExp.getType(), true);
            } else {
              List<BigDecimal> resultAsBigDecimalList = Arrays.stream(exprResult.asDoubleArray()).map(
                  doubleVal -> {
                    if (Double.isNaN(doubleVal) || Double.isInfinite(doubleVal)) {
                      String expression = druidExpression.getExpression();
                      throw new IAE(
                          "'%s' contains an element that evaluates to '%s' which is not supported in SQL. You can either cast the element in the array to bigint or char or change the expression itself",
                          expression,
                          Double.toString(doubleVal)
                      );
                    }
                    return BigDecimal.valueOf(doubleVal);
                  }
              ).collect(Collectors.toList());
              literal = rexBuilder.makeLiteral(resultAsBigDecimalList, constExp.getType(), true);
            }
          } else {
            literal = rexBuilder.makeLiteral(Arrays.asList(exprResult.asArray()), constExp.getType(), true);
          }
        } else if (sqlTypeName == SqlTypeName.OTHER && constExp.getType() instanceof RowSignatures.ComplexSqlType) {
          // complex constant is not reducible, so just leave it as an expression
          literal = constExp;
        } else {
          literal = rexBuilder.makeLiteral(exprResult.value(), constExp.getType(), true);
        }

        reducedValues.add(literal);
      }
    }
  }
}
