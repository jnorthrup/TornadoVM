/*
 * Copyright (c) 2018, 2020, APT Group, Department of Computer Science,
 * The University of Manchester. All rights reserved.
 * Copyright (c) 2009, 2017, Oracle and/or its affiliates. All rights reserved.
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
 * Authors: James Clarkson
 *
 */
package uk.ac.manchester.tornado.runtime.graal.phases;

import static uk.ac.manchester.tornado.runtime.common.Tornado.TORNADO_LOOPS_REVERSE;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.FrameState;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.ValuePhiNode;
import org.graalvm.compiler.nodes.calc.IntegerLessThanNode;
import org.graalvm.compiler.nodes.loop.InductionVariable;
import org.graalvm.compiler.nodes.loop.LoopEx;
import org.graalvm.compiler.nodes.loop.LoopsData;
import org.graalvm.compiler.phases.BasePhase;

import jdk.vm.ci.meta.ResolvedJavaMethod;
import uk.ac.manchester.tornado.api.exceptions.TornadoBailoutRuntimeException;
import uk.ac.manchester.tornado.api.exceptions.TornadoCompilationException;
import uk.ac.manchester.tornado.runtime.ASMClassVisitorProvider;
import uk.ac.manchester.tornado.runtime.common.ParallelAnnotationProvider;
import uk.ac.manchester.tornado.runtime.graal.nodes.ParallelOffsetNode;
import uk.ac.manchester.tornado.runtime.graal.nodes.ParallelRangeNode;
import uk.ac.manchester.tornado.runtime.graal.nodes.ParallelStrideNode;
import uk.ac.manchester.tornado.runtime.graal.nodes.TornadoLoopsData;

public class TornadoApiReplacement extends BasePhase<TornadoSketchTierContext> {

    @Override
    protected void run(StructuredGraph graph, TornadoSketchTierContext context) {
        replaceLocalAnnotations(graph, context);
    }

    /*
     * A singleton is used because we don't need to support all the logic of loading
     * the desired class bytecode and instantiating the helper classes for the ASM
     * library. Therefore, we use the singleton to call
     * ASMClassVisitor::getParallelAnnotations which will handle everything in the
     * right module. We can't have ASMClassVisitor::getParallelAnnotations be a
     * static method because we dynamically load the class and the interface does
     * not allow it.
     */
    private static ASMClassVisitorProvider asmClassVisitorProvider;
    static {
        try {
            String tornadoAnnotationImplementation = System.getProperty("tornado.load.annotation.implementation");
            Class<?> klass = Class.forName(tornadoAnnotationImplementation);
            Constructor<?> constructor = klass.getConstructor();
            asmClassVisitorProvider = (ASMClassVisitorProvider) constructor.newInstance();
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | NoSuchMethodException | SecurityException | IllegalArgumentException | InvocationTargetException e) {
            e.printStackTrace();
            throw new RuntimeException("[ERROR] Tornado Annotation Implementation class not found");
        }
    }

    private void replaceLocalAnnotations(StructuredGraph graph, TornadoSketchTierContext context) throws TornadoCompilationException {
        // build node -> annotation mapping
        Map<ResolvedJavaMethod, ParallelAnnotationProvider[]> methodToAnnotations = new HashMap<>();

        methodToAnnotations.put(context.getMethod(), asmClassVisitorProvider.getParallelAnnotations(context.getMethod()));

        for (ResolvedJavaMethod inlinee : graph.getMethods()) {
            ParallelAnnotationProvider[] inlineParallelAnnotations = asmClassVisitorProvider.getParallelAnnotations(inlinee);
            if (inlineParallelAnnotations.length > 0) {
                methodToAnnotations.put(inlinee, inlineParallelAnnotations);
            }
        }

        Map<Node, ParallelAnnotationProvider> parallelNodes = new HashMap<>();

        graph.getNodes().filter(FrameState.class).forEach((fs) -> {
            if (methodToAnnotations.containsKey(fs.getMethod())) {
                for (ParallelAnnotationProvider an : methodToAnnotations.get(fs.getMethod())) {
                    if (fs.bci >= an.getStart() && fs.bci < an.getStart() + an.getLength()) {
                        Node localNode = fs.localAt(an.getIndex());
                        if (!parallelNodes.containsKey(localNode)) {
                            parallelNodes.put(localNode, an);
                        }
                    }
                }
            }
        });

        if (graph.hasLoops()) {
            final LoopsData data = new TornadoLoopsData(graph);
            data.detectCountedLoops();
            int loopIndex = 0;
            final List<LoopEx> loops = data.outerFirst();
            if (TORNADO_LOOPS_REVERSE) {
                Collections.reverse(loops);
            }

            for (LoopEx loop : loops) {
                for (InductionVariable iv : loop.getInductionVariables().getValues()) {
                    if (!parallelNodes.containsKey(iv.valueNode())) {
                        continue;
                    }
                    ValueNode maxIterations;
                    List<IntegerLessThanNode> conditions = iv.valueNode().usages().filter(IntegerLessThanNode.class).snapshot();

                    final IntegerLessThanNode lessThan = conditions.get(0);

                    maxIterations = lessThan.getY();

                    parallelizationReplacement(graph, iv, loopIndex, maxIterations, conditions);

                    loopIndex++;
                }
            }
        }
    }

    private void parallelizationReplacement(StructuredGraph graph, InductionVariable inductionVar, int loopIndex, ValueNode maxIterations, List<IntegerLessThanNode> conditions)
            throws TornadoCompilationException {
        if (inductionVar.isConstantInit() && inductionVar.isConstantStride()) {

            final ConstantNode newInit = graph.addWithoutUnique(ConstantNode.forInt((int) inductionVar.constantInit()));

            final ConstantNode newStride = graph.addWithoutUnique(ConstantNode.forInt((int) inductionVar.constantStride()));

            final ParallelOffsetNode offset = graph.addWithoutUnique(new ParallelOffsetNode(loopIndex, newInit));

            final ParallelStrideNode stride = graph.addWithoutUnique(new ParallelStrideNode(loopIndex, newStride));

            final ParallelRangeNode range = graph.addWithoutUnique(new ParallelRangeNode(loopIndex, maxIterations, offset, stride));

            final ValuePhiNode phi = (ValuePhiNode) inductionVar.valueNode();

            final ValueNode oldStride = phi.singleBackValueOrThis();

            if (oldStride.usages().count() > 1) {
                final ValueNode duplicateStride = (ValueNode) oldStride.copyWithInputs(true);
                oldStride.replaceAtMatchingUsages(duplicateStride, usage -> !usage.equals(phi));
            }

            inductionVar.initNode().replaceAtMatchingUsages(offset, node -> node.equals(phi));
            inductionVar.strideNode().replaceAtMatchingUsages(stride, node -> node.equals(oldStride));
            // only replace this node in the loop condition
            maxIterations.replaceAtMatchingUsages(range, node -> node.equals(conditions.get(0)));

        } else {
            throw new TornadoBailoutRuntimeException("Failed to parallelize because of non-constant loop strides. \nSequential code will run on the device!");
        }
    }
}
