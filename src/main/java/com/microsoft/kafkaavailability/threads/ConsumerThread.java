//*********************************************************
// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.
//*********************************************************

package com.microsoft.kafkaavailability.threads;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SlidingWindowReservoir;
import com.google.gson.Gson;
import com.microsoft.kafkaavailability.*;
import com.microsoft.kafkaavailability.discovery.CommonUtils;
import com.microsoft.kafkaavailability.metrics.AvailabilityGauge;
import com.microsoft.kafkaavailability.metrics.MetricNameEncoded;
import com.microsoft.kafkaavailability.metrics.MetricsFactory;
import com.microsoft.kafkaavailability.properties.AppProperties;
import com.microsoft.kafkaavailability.properties.ConsumerProperties;
import com.microsoft.kafkaavailability.properties.MetaDataManagerProperties;
import org.apache.curator.framework.CuratorFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Phaser;

public class ConsumerThread implements Runnable {

    final static Logger m_logger = LoggerFactory.getLogger(ConsumerThread.class);
    Phaser m_phaser;
    CuratorFramework m_curatorFramework;
    List<String> m_listServers;
    String m_serviceSpec;
    String m_clusterName;
    MetricsFactory metricsFactory;

    public ConsumerThread(Phaser phaser, CuratorFramework curatorFramework, List<String> listServers, String serviceSpec, String clusterName) {
        this.m_phaser = phaser;
        this.m_curatorFramework = curatorFramework;
        this.m_phaser.register(); //Registers/Add a new unArrived party to this phaser.
        CommonUtils.dumpPhaserState("After registration of ConsumerThread", phaser);
        m_listServers = listServers;
        m_serviceSpec = serviceSpec;
        m_clusterName = clusterName;
    }

    @Override
    public void run() {
        do {
            long lStartTime = System.nanoTime();
            MetricRegistry metrics;
            m_logger.info(Thread.currentThread().getName() +
                    " - Consumer party has arrived and is working in "
                    + "Phase-" + m_phaser.getPhase());

            try {
                metricsFactory = MetricsFactory.getInstance();
                metricsFactory.configure(m_clusterName);

                metricsFactory.start();
                metrics = metricsFactory.getRegistry();
                RunConsumer(metrics);
                metricsFactory.report();
                CommonUtils.sleep(1000);
            } catch (Exception e) {
                m_logger.error(e.getMessage(), e);
            } finally {
                try {
                    metricsFactory.stop();
                } catch (Exception e) {
                    m_logger.error(e.getMessage(), e);
                }
            }

            m_logger.info("Consumer Elapsed: " + CommonUtils.stopWatch(lStartTime) + " milliseconds.");
            m_phaser.arriveAndDeregister();
            CommonUtils.dumpPhaserState("After arrival of ConsumerThread", m_phaser);
        } while (!m_phaser.isTerminated());
        m_logger.info("ConsumerThread (run()) has been COMPLETED.");
    }

    private void RunConsumer(MetricRegistry metrics) throws IOException, MetaDataManagerException {

        m_logger.info("Starting ConsumerLatency");

        IPropertiesManager consumerPropertiesManager = new PropertiesManager<ConsumerProperties>("consumerProperties.json", ConsumerProperties.class);
        IPropertiesManager metaDataPropertiesManager = new PropertiesManager<MetaDataManagerProperties>("metadatamanagerProperties.json", MetaDataManagerProperties.class);
        IMetaDataManager metaDataManager = new MetaDataManager(m_curatorFramework, metaDataPropertiesManager);
        MetaDataManagerProperties metaDataProperties = (MetaDataManagerProperties) metaDataPropertiesManager.getProperties();
        IConsumer consumer = new Consumer(consumerPropertiesManager, metaDataManager);

        IPropertiesManager appPropertiesManager = new PropertiesManager<AppProperties>("appProperties.json", AppProperties.class);
        AppProperties appProperties = (AppProperties) appPropertiesManager.getProperties();

        long startTime, endTime;
        int numPartitionsConsumers = 0;

        //This is full list of topics
        List<kafka.javaapi.TopicMetadata> totalTopicMetadata = metaDataManager.getAllTopicPartition();
        List<kafka.javaapi.TopicMetadata> allTopicMetadata = new ArrayList<kafka.javaapi.TopicMetadata>();

        for (kafka.javaapi.TopicMetadata topic : totalTopicMetadata) {
            if ((totalTopicMetadata.indexOf(topic) % m_listServers.size()) == m_listServers.indexOf(m_serviceSpec)) {
                allTopicMetadata.add(topic);
            }
        }

        m_logger.info("totalTopicMetadata size:" + totalTopicMetadata.size());
        m_logger.info("allTopicMetadata size in Consumer:" + allTopicMetadata.size());

        int consumerTryCount = 0;
        int consumerFailCount = 0;

        for (kafka.javaapi.TopicMetadata topic : allTopicMetadata) {
            numPartitionsConsumers += topic.partitionsMetadata().size();
        }

        final SlidingWindowReservoir consumerLatencyWindow = new SlidingWindowReservoir(numPartitionsConsumers);
        Histogram histogramConsumerLatency = new Histogram(consumerLatencyWindow);
        MetricNameEncoded consumerLatency = new MetricNameEncoded("Consumer.Latency", "all");
        if (!metrics.getNames().contains(new Gson().toJson(consumerLatency))) {
            if (appProperties.sendConsumerLatency) {
                metrics.register(new Gson().toJson(consumerLatency), histogramConsumerLatency);
            }
        }
        for (kafka.javaapi.TopicMetadata item : allTopicMetadata) {
            m_logger.info("Reading from Topic: {};", item.topic());
            final SlidingWindowReservoir topicLatency = new SlidingWindowReservoir(item.partitionsMetadata().size());
            Histogram histogramConsumerTopicLatency = new Histogram(topicLatency);
            MetricNameEncoded consumerTopicLatency = new MetricNameEncoded("Consumer.Topic.Latency", item.topic());
            if (!metrics.getNames().contains(new Gson().toJson(consumerTopicLatency))) {
                if (appProperties.sendConsumerTopicLatency)
                    metrics.register(new Gson().toJson(consumerTopicLatency), histogramConsumerTopicLatency);
            }

            for (kafka.javaapi.PartitionMetadata part : item.partitionsMetadata()) {
                m_logger.debug("Reading from Topic: {}; Partition: {};", item.topic(), part.partitionId());
                MetricNameEncoded consumerPartitionLatency = new MetricNameEncoded("Consumer.Partition.Latency", item.topic() + "-" + part.partitionId());
                Histogram histogramConsumerPartitionLatency = new Histogram(new SlidingWindowReservoir(1));
                if (!metrics.getNames().contains(new Gson().toJson(consumerPartitionLatency))) {
                    if (appProperties.sendConsumerPartitionLatency)
                        metrics.register(new Gson().toJson(consumerPartitionLatency), histogramConsumerPartitionLatency);
                }

                startTime = System.currentTimeMillis();
                try {
                    consumerTryCount++;
                    consumer.ConsumeFromTopicPartition(item.topic(), part.partitionId());
                    endTime = System.currentTimeMillis();
                } catch (Exception e) {
                    m_logger.error("Error Reading from Topic: {}; Partition: {}; Exception: {}", item.topic(), part.partitionId(), e);
                    consumerFailCount++;
                    endTime = System.currentTimeMillis() + 60000;
                    consumerFailCount++;
                }
                histogramConsumerLatency.update(endTime - startTime);
                histogramConsumerTopicLatency.update(endTime - startTime);
                histogramConsumerPartitionLatency.update(endTime - startTime);
            }
        }

        if (appProperties.sendConsumerAvailability) {
            MetricNameEncoded consumerAvailability = new MetricNameEncoded("Consumer.Availability", "all");
            if (!metrics.getNames().contains(new Gson().toJson(consumerAvailability))) {
                metrics.register(new Gson().toJson(consumerAvailability), new AvailabilityGauge(consumerTryCount, consumerTryCount - consumerFailCount));
            }
        }

        ((MetaDataManager) metaDataManager).close();
        m_logger.info("Finished ConsumerLatency");
    }
}