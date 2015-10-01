package net.redborder.snmp.managers;

import kafka.javaapi.producer.Producer;
import kafka.producer.KeyedMessage;
import kafka.producer.ProducerConfig;
import net.redborder.snmp.util.Configuration;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;

public class KafkaManager extends Thread {
    private static final Logger log = LoggerFactory.getLogger(KafkaManager.class);

    // The kafka producer
    private Producer<String, String> producer;

    // A JSON parser from Jackson
    private ObjectMapper objectMapper = new ObjectMapper();

    private LinkedBlockingQueue<Map<String, Object>> queue;


    /**
     * Creates a new KafkaSink.
     * This method initializes and starts a new Kafka producer that will be
     * used to produce messages to kafka topics.
     */

    public KafkaManager(LinkedBlockingQueue<Map<String, Object>> queue) {
        this.queue=queue;
        Configuration configuration = Configuration.getConfiguration();
        // The producer config attributes
        Properties props = new Properties();
        props.put("metadata.broker.list", configuration.getFromGeneral(Configuration.Dimensions.KAFKABROKERS));
        props.put("serializer.class", "kafka.serializer.StringEncoder");
        props.put("request.required.acks", "1");
        props.put("message.send.max.retries", "60");
        props.put("retry.backoff.ms", "1000");
        props.put("producer.type", "async");
        props.put("queue.buffering.max.messages", "10000");
        props.put("queue.buffering.max.ms", "500");
        props.put("partitioner.class", "net.redborder.snmp.util.SimplePartitioner");

        // Initialize the producer
        ProducerConfig config = new ProducerConfig(props);
        producer = new Producer<>(config);
    }

    @Override
    public void run() {

        while (isInterrupted()) {
            Map<String, Object> event = null;

            try {
                event = queue.take();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            if (event != null) {
                String directions[] = new String[]{"ingress", "egress"};
                Map<String, Object> state = new HashMap<>();
                Long time_now = System.currentTimeMillis() / 1000;
                Map<String, Object> enrichment = (Map<String, Object>) event.get("enrichment");

                state.put("wireless_station", event.get("mac_address"));
                state.put("wireless_station_ip", event.get("ip_address"));
                state.put("type", "snmp_apMonitor");
                state.put("timestamp", time_now);
                state.put("status", event.get("status"));
                state.put("sensor_ip", event.get("sensor_ip"));
                state.putAll(enrichment);

                if ((Boolean) event.get("is_first")) {
                    for (String direction : directions) {
                        Map<String, Object> directionStats = new HashMap<>();
                        Long bytes = (Long) event.get("sent_bytes");
                        Long pkts = (Long) event.get("sent_pkts");
                        if (direction.equals("ingress")) {
                            bytes = (Long) event.get("recv_bytes");
                            pkts = (Long) event.get("recv_pkts");
                        }

                        directionStats.put("bytes", bytes);
                        directionStats.put("pkts", pkts);
                        directionStats.put("direction", direction);
                        directionStats.put("timestamp", time_now);
                        directionStats.put("time_switched", time_now - (5 * 60));
                        directionStats.put("sensor_ip", "");
                        directionStats.put("wireless_station", event.get("mac_address"));
                        directionStats.put("wireless_station_ip", event.get("ip_address"));
                        directionStats.put("device_category", "stations");
                        directionStats.put("type", "snmpstats");
                        directionStats.put("sensor_ip", event.get("sensor_ip"));
                        directionStats.putAll(enrichment);

                        send("rb_flow", (String) event.get("mac_address"), directionStats);
                    }
                }
                send("rb_state", (String) event.get("mac_address"), state);
            }
        }
    }

    /**
     * Stops the kafka producer and releases its resources.
     */

    public void shutdown() {
        producer.close();
    }

    /**
     * This method sends a given message, with a given key to a given kafka topic.
     *
     * @param topic   The topic where the message will be sent
     * @param key     The key of the message
     * @param message The message to send
     */

    public void send(String topic, String key, Map<String, Object> message) {
        try {
            String messageStr = objectMapper.writeValueAsString(message);
            KeyedMessage<String, String> keyedMessage = new KeyedMessage<>(topic, key, messageStr);
            producer.send(keyedMessage);
        } catch (IOException e) {
            log.error("Error converting map to json: {}", message);
        }
    }
}
