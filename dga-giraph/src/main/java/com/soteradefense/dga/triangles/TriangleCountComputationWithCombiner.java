package com.soteradefense.dga.triangles;


import com.soteradefense.dga.DGALoggingUtil;
import org.apache.giraph.comm.WorkerClientRequestProcessor;
import org.apache.giraph.edge.Edge;
import org.apache.giraph.graph.BasicComputation;
import org.apache.giraph.graph.GraphState;
import org.apache.giraph.graph.GraphTaskManager;
import org.apache.giraph.graph.Vertex;
import org.apache.giraph.worker.WorkerContext;
import org.apache.giraph.worker.WorkerGlobalCommUsage;
import org.apache.hadoop.io.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.HashSet;
import java.util.ArrayList;



/**
 * Created by ekimbrel on 9/22/15.
 */
/*
 *
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *       http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */



/**
 * Counts the number of triangles (or loops of length 3) in the Graph.  Assumes an undirected Graph.
 *
 *
 * <I,V,E,M>
 * I - Vertex id
 * V - Vertex Data
 * E - Edge Data
 * M - Message type
 *
 */
public class TriangleCountComputationWithCombiner extends BasicComputation<IntWritable, IntWritable, NullWritable, ArrayPrimitiveWritable> {

    private static final Logger logger = LoggerFactory.getLogger(TriangleCountComputation.class);


    @Override
    public void initialize(GraphState graphState, WorkerClientRequestProcessor<IntWritable, IntWritable, NullWritable> workerClientRequestProcessor, GraphTaskManager<IntWritable, IntWritable, NullWritable> graphTaskManager, WorkerGlobalCommUsage workerGlobalCommUsage, WorkerContext workerContext) {
        super.initialize(graphState, workerClientRequestProcessor, graphTaskManager, workerGlobalCommUsage, workerContext);
        DGALoggingUtil.setDGALogLevel(this.getConf());
    }

    @Override
    public void compute(Vertex<IntWritable, IntWritable, NullWritable> vertex, Iterable<ArrayPrimitiveWritable> messages) throws IOException {


        long step = getSuperstep();
        IntWritable thisVertexId = vertex.getId();

        // keep our neighbors in a set for fast searching and remove the effect of duplicate edges if they exist.
        HashSet<Integer> neighbors = new HashSet<Integer>();
        for (Edge<IntWritable, NullWritable> edge : vertex.getEdges()) {
            neighbors.add(edge.getTargetVertexId().get());
        }
        // remove self edges
        neighbors.remove(thisVertexId.get());


        // step 0, send vertex.id to all neighbors
        if (step == 0L) {
            ArrayPrimitiveWritable outMessage = new ArrayPrimitiveWritable( new int[] {thisVertexId.get()});
            for (int target: neighbors){
                sendMessage(new IntWritable(target),outMessage);
            }
        }



        // step 1, for each message "sourceId" forward the message to all neighbors except the source
        else if (step == 1L) {
            ArrayList<Integer> sources = new ArrayList<Integer>();
            for (ArrayPrimitiveWritable message : messages) {
                int[] messageArray = ((int[]) message.get());
                for (int source: messageArray) sources.add(source);
            }
            int[] primitiveArray = new int[sources.size()];
            int i = 0;
            for (int source: sources) primitiveArray[i++] = source;
            ArrayPrimitiveWritable outMessage = new ArrayPrimitiveWritable(primitiveArray);
            for (int target: neighbors) {
                IntWritable destination = new IntWritable(target);
                sendMessage(destination, outMessage);
            }

        }


        // step 2, for each message  forward it to the originating source
        // if this vertex as an edge to the source
        else if (step == 2L) {

            // collect all sources recieved in a single array
            int thisId = thisVertexId.get();
            ArrayList<Integer> sources = new ArrayList<Integer>();
            for (ArrayPrimitiveWritable message : messages) {
                int[] messageArray = ((int[]) message.get());
                for (int sourceId: messageArray){
                    if (sourceId != thisId) {
                        sources.add(sourceId);
                    }
                }
            }

            for (int source: sources) {
                if (neighbors.contains(source)){
                    sendMessage(new IntWritable(source),new ArrayPrimitiveWritable(new int[]{source}));
                }
            }
        }


        // step 3, count messages and aggregate total triangle count
        else if (step == 3L) {

            int numTriangles = 0;

            for (ArrayPrimitiveWritable message : messages) {
                int[] messageArray = ((int[]) message.get());
                numTriangles += messageArray.length;
            }

            // because messages go in both directions we'll have counted each triangle twice at each node
            numTriangles = numTriangles / 2;
            vertex.setValue(new IntWritable(numTriangles));
            this.aggregate(TriangleCountMasterCompute.TRI_COUNT_AGG, new LongWritable(numTriangles));
        }

        else{
            vertex.voteToHalt();
        }

    }


}
