/*
 * Copyright (c) 2013-2018, APT Group, School of Computer Science,
 * The University of Manchester.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *    http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package uk.ac.manchester.tornado.examples.fpga;

import static uk.ac.manchester.tornado.api.collections.math.TornadoMath.abs;

import java.io.PrintStream;
import java.util.Arrays;

import uk.ac.manchester.tornado.api.Policy;
import uk.ac.manchester.tornado.api.TaskSchedule;
import uk.ac.manchester.tornado.api.annotations.Parallel;
import uk.ac.manchester.tornado.api.collections.math.TornadoMath;

public class NBodyFPGA {

    private static float delT,espSqr;
    private static float[] posSeq,velSeq;
    private static int[] inputSize;
    private static int numBodies;
    private static TaskSchedule graph;
    private static boolean VALIDATION = false;

    private static void usage(String[] args) {
        final PrintStream printf = System.err.printf("Usage: Number of bodies is missing or number of iterations\n");
        System.exit(1);
    }

    private static void nBody(int numBodies, float[] refPos, float[] refVel, float delT, float espSqr, int[] inputSize) {
        for (@Parallel int i = 0; i < numBodies; i++) {
            int body = 4 * i;
            float[] acc = new float[] { 0.0f, 0.0f, 0.0f };
            for (int j = 0; j < inputSize[0]; j++) {
                float[] r = new float[3];
                int index = 4 * j;

                float distSqr = 0.0f;
                for (int k = 0; k < 3; k++) {
                    r[k] = refPos[index + k] - refPos[body + k];
                    distSqr += r[k] * r[k];
                }

                float invDist = (float) (1.0f / (float) TornadoMath.floatSqrt(distSqr + espSqr));

                float invDistCube = invDist * invDist * invDist;
                float s = refPos[index + 3] * invDistCube;

                for (int k = 0; k < 3; k++) {
                    acc[k] += s * r[k];
                }
            }
            for (int k = 0; k < 3; k++) {
                refPos[body + k] += refVel[body + k] * delT + 0.5f * acc[k] * delT * delT;
                refVel[body + k] += acc[k] * delT;
            }
        }
    }

    public static boolean validate() {
        boolean val = true;
        float[] posSeqSeq,velSeqSeq;
        delT = 0.005f;
        espSqr = 500.0f;

        float[] auxPositionRandom = new float[numBodies * 4];
        float[] auxVelocityZero = new float[numBodies * 3];

        for (int i = 0; i < auxPositionRandom.length; i++) {
            auxPositionRandom[i] = (float) Math.random();
        }

        Arrays.fill(auxVelocityZero, 0.0f);

        posSeq = new float[numBodies * 4];
        velSeq = new float[numBodies * 4];
        posSeqSeq = new float[numBodies * 4];
        velSeqSeq = new float[numBodies * 4];

        for (int i = 0; i < auxPositionRandom.length; i++) {
            posSeq[i] = auxPositionRandom[i];
            posSeqSeq[i] = auxPositionRandom[i];
        }
        for (int i = 0; i < auxVelocityZero.length; i++) {
            velSeq[i] = auxVelocityZero[i];
            velSeqSeq[i] = auxVelocityZero[i];
        }
        graph = new TaskSchedule("s0");
        graph.task("t0", NBodyFPGA::nBody, numBodies, posSeq, velSeq, delT, espSqr, inputSize).streamOut(posSeq, velSeq).streamOut(posSeq, velSeq);
        graph.warmup();
        graph.execute();

        nBody(numBodies, posSeqSeq, velSeqSeq, delT, espSqr, inputSize);

        for (int i = 0; i < numBodies * 4; i++) {
            if (abs(posSeqSeq[i] - posSeq[i]) > 0.01) {
                val = false;
                break;
            }
            if (abs(velSeq[i] - velSeqSeq[i]) > 0.01) {
                val = false;
                break;
            }
        }
        System.out.printf("Number validation: " + val + "\n");
        return val;
    }

    public static void main(String[] args) {

        StringBuffer resultsIterations = new StringBuffer();

        if (args.length != 3) {
            usage(args);
        }

        numBodies = Integer.parseInt(args[0]);
        String executionType = args[1];
        final int iterations = Integer.parseInt(args[2]);
        long end,start;

        inputSize = new int[1];
        inputSize[0] = numBodies;

        delT = 0.005f;
        espSqr = 500.0f;

        float[] auxPositionRandom = new float[numBodies * 4];
        float[] auxVelocityZero = new float[numBodies * 3];

        for (int i = 0; i < auxPositionRandom.length; i++) {
            auxPositionRandom[i] = (float) Math.random();
        }

        Arrays.fill(auxVelocityZero, 0.0f);

        posSeq = new float[numBodies * 4];
        velSeq = new float[numBodies * 4];

        for (int i = 0; i < auxPositionRandom.length; i++) {
            posSeq[i] = auxPositionRandom[i];
        }
        for (int i = 0; i < auxVelocityZero.length; i++) {
            velSeq[i] = auxVelocityZero[i];
        }

        final TaskSchedule s0 = new TaskSchedule("s0").task("t0", NBodyFPGA::nBody, numBodies, posSeq, velSeq, delT, espSqr, inputSize).streamOut(posSeq, velSeq);

        for (int i = 0; i < iterations; i++) {
            switch (executionType) {
                case "performance":
                    start = System.nanoTime();
                    s0.executeWithProfilerSequential(Policy.PERFORMANCE);
                    end = System.nanoTime();
                    break;
                case "end":
                    start = System.nanoTime();
                    s0.executeWithProfilerSequential(Policy.END_2_END);
                    end = System.nanoTime();
                    break;
                default:
                    start = System.nanoTime();
                    s0.execute();
                    end = System.nanoTime();
            }
            System.out.println("End to end time:  " + (end - start) + " ns" + "\n");
        }

        if (VALIDATION) {
            validate();
        }
    }

}
