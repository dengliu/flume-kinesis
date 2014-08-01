/**
 *  Copyright 2014 TangoMe Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.tango.flume.kinesis.sink;

import com.amazonaws.auth.BasicAWSCredentials;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import org.apache.commons.lang.StringUtils;
import org.apache.flume.*;
import org.apache.flume.conf.Configurable;
import org.apache.flume.sink.AbstractSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import com.amazonaws.AmazonClientException;
import com.amazonaws.services.kinesis.AmazonKinesisClient;
import com.amazonaws.services.kinesis.model.PutRecordRequest;
import com.amazonaws.services.kinesis.model.PutRecordResult;

public class KinesisSink extends AbstractSink implements Configurable {
    private static final Logger logger = LoggerFactory.getLogger(KinesisSink.class);
    static AmazonKinesisClient kinesisClient;

    /**
     * Configuration attributes
     */

    private String accessKey = null;
    private String accessSecretKey = null;
    private String streamName = null;
    private String numberOfPartitions = null;
    private String kinesisEndpoint = null;
    private Integer batchSize = null;


    public KinesisSink() {

    }

    @VisibleForTesting
    public KinesisSink(AmazonKinesisClient kinesisClient) {
        this.kinesisClient = kinesisClient;
        this.kinesisClient.setEndpoint(kinesisEndpoint);
    }

    @Override
    public synchronized void start() {
        logger.info("Starting KinesisSink: ");
        if (this.kinesisClient == null) {
            this.kinesisClient = new AmazonKinesisClient(new BasicAWSCredentials(this.accessKey, this.accessSecretKey));
        }
        this.kinesisClient.setEndpoint(kinesisEndpoint);
        super.start();
    }

    @Override
    public synchronized void stop() {
        logger.info("Stoping KinesisSink");
        super.stop();
    }

    @Override
    public Status process() throws EventDeliveryException {
        Status status = Status.READY;

        // Start transaction
        List<Event> batchEvents = new ArrayList<Event>(batchSize);
        Channel ch = getChannel();
        Transaction txn = ch.getTransaction();

        try {
            txn.begin();

            for (int i = 0; i < batchSize && status != Status.BACKOFF; i++) {
                Event event = ch.take();
                if (event == null) {
                    status = Status.BACKOFF;
                } else {
                    batchEvents.add(event);
                }
            }

            /**
             * Only send events if we got any
             */
            if (batchEvents.size() > 0) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Sending " + batchEvents.size() + " events");
                }
                for (Event event : batchEvents) {
                    int partitionKey = new Random().nextInt((Integer.valueOf(numberOfPartitions) - 1) + 1) + 1;
                    PutRecordRequest putRecordRequest = new PutRecordRequest();
                    putRecordRequest.setStreamName(this.streamName);
                    putRecordRequest.setData(ByteBuffer.wrap(event.getBody()));
                    putRecordRequest.setPartitionKey("partitionKey_" + partitionKey);
                    PutRecordResult putRecordResult = kinesisClient.putRecord(putRecordRequest);
                    if (logger.isDebugEnabled()) {
                        logger.debug("Record put to kinesis ---{}", event.toString());
                    }
                }
            }

            txn.commit();
        } catch (AmazonClientException e) {
            logger.error("Can't put record to amazon kinesis -- {}", e);
            txn.rollback();
            status = Status.BACKOFF;
        } catch (Throwable t) {
            txn.rollback();
            // Log exception, handle individual exceptions as needed
            status = Status.BACKOFF;
            logger.error("Transaction failed " + t);
            // re-throw all Errors
            if (t instanceof Error) {
                throw (Error)t;
            }
        } finally {
            txn.close();
        }


        return status;
    }

    @Override
    public void configure(Context context) {
        this.accessKey = context.getString(KinesisSinkConfigurationConstant.ACCESSKEY);
        if (StringUtils.isBlank(this.accessKey)) {
            logger.error("Access key cannot be blank");
            throw new IllegalArgumentException("Access key cannot be blank");
        }

        this.accessSecretKey = context.getString(KinesisSinkConfigurationConstant.ACCESSSECRETKEY);
        if (StringUtils.isBlank(this.accessSecretKey)) {
            logger.error("Access secret key cannot be blank");
            throw new IllegalArgumentException("Access secret key cannot be blank");
        }

        this.numberOfPartitions = context.getString(KinesisSinkConfigurationConstant.KINESISPARTITIONS, "2");

        this.streamName = context.getString(KinesisSinkConfigurationConstant.STREAMNAME);
        if(StringUtils.isBlank(this.streamName)){
            logger.error("Stream name cannot be blank");
            throw new IllegalArgumentException("Stream name cannot be blank");
        }

        this.kinesisEndpoint = context.getString(KinesisSinkConfigurationConstant.KINESISENDPOINT,"https://kinesis.us-west-2.amazonaws.com");
        batchSize = context.getInteger(KinesisSinkConfigurationConstant.BATCH_SIZE,
                KinesisSinkConfigurationConstant.DEFAULT_BATCH_SIZE);
        Preconditions.checkState(batchSize > 0, KinesisSinkConfigurationConstant.BATCH_SIZE
                + " parameter must be greater than 1");

    }
}