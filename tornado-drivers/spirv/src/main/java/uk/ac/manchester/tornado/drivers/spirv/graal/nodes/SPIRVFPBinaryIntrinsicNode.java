/*
 * This file is part of Tornado: A heterogeneous programming framework:
 * https://github.com/beehive-lab/tornadovm
 *
 * Copyright (c) 2022, APT Group, Department of Computer Science,
 * School of Engineering, The University of Manchester. All rights reserved.
 * Copyright (c) 2009-2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */
package uk.ac.manchester.tornado.drivers.spirv.graal.nodes;

import org.graalvm.compiler.core.common.type.Stamp;
import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.graph.NodeClass;
import org.graalvm.compiler.lir.Variable;
import org.graalvm.compiler.lir.gen.ArithmeticLIRGeneratorTool;
import org.graalvm.compiler.nodeinfo.NodeInfo;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.NodeView;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.calc.BinaryNode;
import org.graalvm.compiler.nodes.spi.ArithmeticLIRLowerable;
import org.graalvm.compiler.nodes.spi.CanonicalizerTool;
import org.graalvm.compiler.nodes.spi.NodeLIRBuilderTool;

import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.Value;
import uk.ac.manchester.tornado.api.exceptions.TornadoInternalError;
import uk.ac.manchester.tornado.drivers.spirv.graal.compiler.lir.SPIRVArithmeticTool;
import uk.ac.manchester.tornado.drivers.spirv.graal.lir.SPIRVBuiltinTool;
import uk.ac.manchester.tornado.drivers.spirv.graal.lir.SPIRVLIRStmt;
import uk.ac.manchester.tornado.runtime.graal.phases.MarkFloatingPointIntrinsicsNode;

@NodeInfo(nameTemplate = "{p#operation/s}")
public class SPIRVFPBinaryIntrinsicNode extends BinaryNode implements ArithmeticLIRLowerable, MarkFloatingPointIntrinsicsNode {

    // @formatter:off
    public enum SPIRVOperation {
        ATAN2,
        ATAN2PI,
        COPYSIGN,
        FDIM,
        FMA,
        FMAX,
        FMIN,
        FMOD,
        FRACT,
        FREXP,
        HYPOT,
        LDEXP,
        MAD,
        MAXMAG,
        MINMAG,
        MODF,
        NEXTAFTER,
        POW,
        POWN,
        POWR,
        REMAINDER,
        REMQUO,
        ROOTN,
        SINCOS
    }
    // @formatter:on

    protected final SPIRVOperation operation;
    public static final NodeClass<SPIRVFPBinaryIntrinsicNode> TYPE = NodeClass.create(SPIRVFPBinaryIntrinsicNode.class);

    protected SPIRVFPBinaryIntrinsicNode(ValueNode x, ValueNode y, SPIRVOperation op, JavaKind kind) {
        super(TYPE, StampFactory.forKind(kind), x, y);
        this.operation = op;
    }

    public static ValueNode create(ValueNode x, ValueNode y, SPIRVOperation op, JavaKind kind) {
        ValueNode c = tryConstantFold(x, y, op, kind);
        if (c != null) {
            return c;
        }
        return new SPIRVFPBinaryIntrinsicNode(x, y, op, kind);
    }

    @Override
    public Stamp foldStamp(Stamp stampX, Stamp stampY) {
        return stamp(NodeView.DEFAULT);
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool) {
        return canonical(tool, getX(), getY());
    }

    private static double doCompute(double x, double y, SPIRVOperation op) {
        switch (op) {
            case ATAN2:
                return Math.atan2(x, y);
            case FMIN:
                return Math.min(x, y);
            case FMAX:
                return Math.max(x, y);
            case POW:
                return Math.pow(x, y);
            default:
                throw new TornadoInternalError("unknown op %s", op);
        }
    }

    private static float doCompute(float x, float y, SPIRVOperation op) {
        switch (op) {
            case ATAN2:
                return (float) Math.atan2(x, y);
            case FMIN:
                return Math.min(x, y);
            case FMAX:
                return Math.max(x, y);
            default:
                throw new TornadoInternalError("unknown op %s", op);
        }
    }

    protected static ValueNode tryConstantFold(ValueNode x, ValueNode y, SPIRVOperation op, JavaKind kind) {
        ConstantNode result = null;

        if (x.isConstant() && y.isConstant()) {
            if (kind == JavaKind.Double) {
                double ret = doCompute(x.asJavaConstant().asDouble(), y.asJavaConstant().asDouble(), op);
                result = ConstantNode.forDouble(ret);
            } else if (kind == JavaKind.Float) {
                float ret = doCompute(x.asJavaConstant().asFloat(), y.asJavaConstant().asFloat(), op);
                result = ConstantNode.forFloat(ret);
            }
        }
        return result;
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool, ValueNode x, ValueNode y) {
        ValueNode c = tryConstantFold(x, y, operation(), getStackKind());
        if (c != null) {
            return c;
        }
        return this;
    }

    @Override
    public void generate(NodeLIRBuilderTool builder) {
        generate(builder, builder.getLIRGeneratorTool().getArithmetic());
    }

    @Override
    public String getOperation() {
        return operation.name();
    }

    public SPIRVOperation operation() {
        return operation;
    }

    @Override
    public void generate(NodeLIRBuilderTool builder, ArithmeticLIRGeneratorTool lirGen) {

        SPIRVBuiltinTool gen = ((SPIRVArithmeticTool) lirGen).getGen().getSpirvBuiltinTool();

        Value x = builder.operand(getX());
        Value y = builder.operand(getY());
        Value result;
        switch (operation()) {
            case ATAN2:
                result = gen.genFloatATan2(x, y);
                break;
            case FMIN:
                result = gen.genFloatMin(x, y);
                break;
            case FMAX:
                result = gen.genFloatMax(x, y);
                break;
            case POW:
                result = gen.genFloatPow(x, y);
                break;
            default:
                throw new RuntimeException("Math operation not supported yet");
        }
        Variable variable = builder.getLIRGeneratorTool().newVariable(result.getValueKind());
        builder.getLIRGeneratorTool().append(new SPIRVLIRStmt.AssignStmt(variable, result));
        builder.setResult(this, variable);
    }

}
