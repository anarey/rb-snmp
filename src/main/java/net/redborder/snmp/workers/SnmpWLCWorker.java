package net.redborder.snmp.workers;

import net.redborder.snmp.tasks.SnmpTask;
import net.redborder.snmp.util.AccessPointStatusDB;
import net.redborder.snmp.util.SnmpOID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snmp4j.CommunityTarget;
import org.snmp4j.Snmp;
import org.snmp4j.TransportMapping;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.*;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import org.snmp4j.util.DefaultPDUFactory;
import org.snmp4j.util.TreeEvent;
import org.snmp4j.util.TreeUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class SnmpWLCWorker extends Worker {

    final Logger log = LoggerFactory.getLogger(SnmpMerakiWorker.class);
    SnmpTask snmpTask;
    ExecutorService executorService = Executors.newFixedThreadPool(5);
    LinkedBlockingQueue<Map<String, Object>> queue;
    AccessPointStatusDB cache = new AccessPointStatusDB();
    Long pullingTime;
    volatile AtomicBoolean running = new AtomicBoolean(false);

    public SnmpWLCWorker(SnmpTask snmpTask, LinkedBlockingQueue<Map<String, Object>> queue) {
        this.snmpTask = snmpTask;
        this.queue = queue;
        this.pullingTime = snmpTask.getPullingTime().longValue();
    }

    @Override
    public void run() {
        try {
            running.set(true);
            log.info("Start snmp worker: {} with community: {}", snmpTask.getIP(), snmpTask.getCommunity());

            Address targetAddress = GenericAddress.parse(snmpTask.getIP() + "/" + snmpTask.getPort());
            TransportMapping transport = new DefaultUdpTransportMapping();
            Snmp snmp = new Snmp(transport);
            transport.listen();

            // setting up target
            CommunityTarget target = new CommunityTarget();
            target.setCommunity(new OctetString(snmpTask.getCommunity()));
            target.setAddress(targetAddress);
            target.setRetries(3);
            target.setTimeout(1000 * 3);
            target.setVersion(SnmpConstants.version2c);

            while (running.get()) {
                Long start = System.currentTimeMillis();
                DefaultPDUFactory defaultPDUFactory = new DefaultPDUFactory();
                TreeUtils treeUtils = new TreeUtils(snmp, defaultPDUFactory);
                List<TreeEvent> events = new ArrayList<>();

                for (OID oid : SnmpOID.WirelessLanController.toList()) {
                    events.addAll(treeUtils.getSubtree(target, oid));
                }

                log.info("Getting from SNMP: {}  - content: {}", snmpTask.getIP(), !events.isEmpty());

                Map<String, String> results = new HashMap<>();

                // Get snmpwalk result.
                for (TreeEvent event : events) {
                    if (event != null) {
                        if (!event.isError()) {
                            VariableBinding[] varBindings = event.getVariableBindings();

                            for (VariableBinding varBinding : varBindings) {
                                results.put(varBinding.getOid().toString(), varBinding.getVariable().toString());
                            }
                        } else {
                            // TODO
                        }
                    } else {
                        // TODO
                    }
                }

                List<String> devicesOIDs = getDevicesOIDs(results);
                List<Map<String, Object>> devicesData = getDevicesData(results, devicesOIDs);

                log.info("SNMP accessPoints from {} count: {}", snmpTask.getIP(), devicesData.size());
                log.info("SNMP response in {} ms.", (System.currentTimeMillis() - start));

                try {
                    for (Map<String, Object> interfaceData : devicesData) {
                        queue.put(interfaceData);
                    }
                    TimeUnit.SECONDS.sleep(pullingTime);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            snmp.close();
            transport.close();
        } catch (IOException e) {
            e.printStackTrace();
            // TODO
        }
    }

    public List<String> getDevicesOIDs(Map<String, String> results) {
        List<String> devicesOIDs = new ArrayList<>();
        for (String key : results.keySet()) {
            if (key.contains(SnmpOID.WirelessLanController.DEV_NAME.toString() + ".")) {
                devicesOIDs.add(key.replace(SnmpOID.WirelessLanController.DEV_NAME.toString() + ".", ""));
            }
        }
        return devicesOIDs;
    }

    public List<Map<String, Object>> getDevicesData(Map<String, String> results, List<String> devicesOIDs) {

        List<Map<String, Object>> devicesData = new ArrayList<>();
        List<String> devicesMacAddress = new ArrayList<>();

        for (String deviceOID : devicesOIDs) {
            String macAddress = results.get(SnmpOID.WirelessLanController.DEV_MAC + "." + deviceOID + ".0");
            devicesMacAddress.add(macAddress);
            Map<String, Object> deviceData = new HashMap<>();

            deviceData.put("validForStats", false);
            deviceData.put("sensorIp", snmpTask.getIP());
            deviceData.put("enrichment", snmpTask.getEnrichment());
            deviceData.put("timeSwitched", pullingTime);

            deviceData.put("devName", results.get(SnmpOID.WirelessLanController.DEV_NAME + "." + deviceOID));
            deviceData.put("devInterfaceMac", macAddress);
            deviceData.put("devClientCount",
                    results.get(SnmpOID.WirelessLanController.DEV_CLIENTS_COUNT + "." + deviceOID));
            deviceData.put("devStatus", "on");

            cache.addCache(macAddress, deviceData);
            devicesData.add(deviceData);
        }

        for (Map.Entry<String, Map<String, Object>> accessPoint : cache.getAccessPoints().entrySet()) {
            if (!devicesMacAddress.contains(accessPoint.getKey().toString())) {
                accessPoint.getValue().put("timeSwitched", pullingTime);
                accessPoint.getValue().put("devClientCount", "0");
                accessPoint.getValue().put("devStatus", "off");
                devicesData.add(accessPoint.getValue());
            }
        }

        return devicesData;
    }

    @Override
    public void shutdown() {
        this.running.set(false);
    }
}
