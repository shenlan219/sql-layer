/**
 * Copyright (C) 2012 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.server.expression.std;

import com.akiban.server.error.InvalidArgumentTypeException;
import com.akiban.server.error.WrongExpressionArityException;
import com.akiban.server.expression.*;
import com.akiban.server.service.functions.Scalar;
import com.akiban.server.types.AkType;
import com.akiban.server.types.NullValueSource;
import com.akiban.server.types.ValueSource;
import com.akiban.sql.StandardException;
import java.math.BigDecimal;
import java.math.RoundingMode;

public class CeilFloorExpression extends AbstractUnaryExpression {
    @Scalar("FLOOR")
    public static final ExpressionComposer FLOOR_COMPOSER = new InternalComposer(CeilFloorName.FLOOR);
    
    @Scalar({"CEIL", "CEILING"})
    public static final ExpressionComposer CEIL_COMPOSER = new InternalComposer(CeilFloorName.CEIL);

    public static enum CeilFloorName
    {
        FLOOR, CEIL;
    }
    
    private final CeilFloorName name;

    private static class InternalComposer extends UnaryComposer
    {
        private final CeilFloorName name;

        public InternalComposer(CeilFloorName funcName)
        {
            this.name = funcName;
        }

        @Override
        public ExpressionType composeType(TypesList argumentTypes) throws StandardException
        {
            int argc = argumentTypes.size();
            if (argc != 1)
                throw new WrongExpressionArityException(1, argc);
            ExpressionType firstExpType = argumentTypes.get(0);
            AkType firstAkType = firstExpType.getType();

            argumentTypes.setType(0, firstAkType);
            if (firstAkType == AkType.VARCHAR)
                firstAkType = AkType.DOUBLE;

            return ExpressionTypes.newType(firstAkType, firstExpType.getPrecision(), firstExpType.getScale());
        }

        @Override
        protected Expression compose(Expression argument)
        {
            return new CeilFloorExpression(argument, name);
        }
    }

    private static class InnerEvaluation extends AbstractUnaryExpressionEvaluation
    {

        private final CeilFloorName name;

        public InnerEvaluation(ExpressionEvaluation eval, CeilFloorName funcName)
        {
            super(eval);
            this.name = funcName;
        }

        @Override
        public ValueSource eval()
        {
            ValueSource firstOperand = operand();
            if (firstOperand.isNull())
                return NullValueSource.only();

            AkType operandType = firstOperand.getConversionType();

            switch (operandType)
            {
                // For any integer type, ROUND/FLOOR/CEIL just return the same value
                case INT: case LONG: case U_BIGINT:
                    valueHolder().copyFrom(firstOperand); break;
                // Math.floor/ceil only with doubles, so we split FLOAT/DOUBLE to be safe with casting
                case DOUBLE: case VARCHAR:
                    double dInput = (operandType == AkType.DOUBLE) ? 
                            firstOperand.getDouble() : Double.parseDouble(firstOperand.getString());
                    double finalDValue = (name == CeilFloorName.FLOOR) ? Math.floor(dInput) : Math.ceil(dInput);
                    valueHolder().putDouble(finalDValue); 
                    break;
                case FLOAT:
                    float fInput = firstOperand.getFloat();
                    float finalFValue = (float) ((name == CeilFloorName.FLOOR) ? Math.floor(fInput) : Math.ceil(fInput));
                    valueHolder().putFloat(finalFValue);
                    break;
                case DECIMAL:
                    BigDecimal decInput = firstOperand.getDecimal();
                    BigDecimal finalDecValue = (name == CeilFloorName.FLOOR) ? 
                            decInput.setScale(0, RoundingMode.FLOOR) : decInput.setScale(0, RoundingMode.CEILING);
                    valueHolder().putDecimal(finalDecValue);
                    break;
                default:
                    throw new InvalidArgumentTypeException(name.name() + operandType.name());
            }
            return valueHolder();
        }
    }
    
    protected CeilFloorExpression(Expression operand, CeilFloorName funcName)
    {
        super(operand.valueType(), operand);
        this.name = funcName;
    }
    
    @Override
    protected String name()
    {
        return name.name();
    }

    @Override
    public ExpressionEvaluation evaluation()
    {
        return new InnerEvaluation(this.operandEvaluation(), name);
    }
}
