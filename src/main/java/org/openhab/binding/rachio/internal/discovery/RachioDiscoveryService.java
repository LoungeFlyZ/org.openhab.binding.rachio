/**
 * Copyright (c) 2010-2018 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.rachio.internal.discovery;

import static org.openhab.binding.rachio.RachioBindingConstants.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.config.discovery.AbstractDiscoveryService;
import org.eclipse.smarthome.config.discovery.DiscoveryResult;
import org.eclipse.smarthome.config.discovery.DiscoveryResultBuilder;
import org.eclipse.smarthome.config.discovery.DiscoveryService;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.eclipse.smarthome.core.thing.binding.ThingHandlerService;
import org.openhab.binding.rachio.handler.RachioBridgeHandler;
import org.openhab.binding.rachio.internal.RachioConfiguration;
import org.openhab.binding.rachio.internal.api.RachioApi;
import org.openhab.binding.rachio.internal.api.RachioApiException;
import org.openhab.binding.rachio.internal.api.RachioDevice;
import org.openhab.binding.rachio.internal.api.RachioZone;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link RachioDiscoveryService} is responsible for processing the
 * results of devices found through the Rachio cloud service.
 *
 * @author Markus Michels (markus7017)- Initial contribution
 */
public class RachioDiscoveryService extends AbstractDiscoveryService 
        implements DiscoveryService, ThingHandlerService {

    private static final int DISCOVERY_REFRESH_SEC = 900;

    private final Logger logger = LoggerFactory.getLogger(RachioDiscoveryService.class);
    private Future<?> scanTask;
    private ScheduledFuture<?> discoveryJob;
    

    private RachioApi rachioApi;

    private RachioBridgeHandler cloudHandler;

    public RachioDiscoveryService() {
        super(SUPPORTED_THING_TYPES_UIDS, BINDING_DISCOVERY_TIMEOUT, true);
        String uids = SUPPORTED_THING_TYPES_UIDS.toString();
        logger.debug("Rachio: thing types: {} registered.", uids);
    }

    @Override
    @Activate
    public void activate() {
        super.activate(null);
    }

    @Override
    @Deactivate
    public void deactivate() {
        super.deactivate();
    }

    @Override
    public void setThingHandler(ThingHandler handler) {
        if (handler instanceof RachioBridgeHandler) {
            this.cloudHandler = (RachioBridgeHandler) handler;
        }
    }

    @Override
    public @Nullable ThingHandler getThingHandler() {
        return this.cloudHandler;
    }

    @Override
    protected void startBackgroundDiscovery() {
        logger.debug("Starting background discovery for new Rachio controllers");

        ScheduledFuture<?> discoveryJob = this.discoveryJob;
        if (discoveryJob == null || discoveryJob.isCancelled()) {
            discoveryJob = scheduler.scheduleWithFixedDelay(this::discover, 10, DISCOVERY_REFRESH_SEC,
                    TimeUnit.SECONDS);
        }
    }

    @Override
    protected synchronized void startScan() {
        Future<?> scanTask = this.scanTask;
        if (scanTask == null || scanTask.isDone()) {
            logger.debug("Starting Rachio discovery scan");
            scanTask = scheduler.submit(this::discover);
        }
    }

    protected synchronized void discover() {

        HashMap<String, RachioDevice> deviceList = null;
        ThingUID bridgeUID;

        if (cloudHandler == null) {
            logger.debug("RachioDiscovery: Rachio Cloud access not set!");
            return;
        }

        deviceList = cloudHandler.getDevices();
        bridgeUID = cloudHandler.getThing().getUID();

        if (deviceList == null) {
            logger.debug("RachioDiscovery: Rachio Cloud access not initialized yet!");
            return;
        }
        logger.debug("RachioDiscovery: Found {} devices.", deviceList.size());
        for (HashMap.Entry<String, RachioDevice> de : deviceList.entrySet()) {
            RachioDevice dev = de.getValue();
            logger.debug("RachioDiscovery: Check Rachio device with ID '{}'", dev.id);

            // register thing if it not already exists
            ThingUID devThingUID = new ThingUID(THING_TYPE_DEVICE, bridgeUID, dev.getThingID());
            dev.setUID(bridgeUID, devThingUID);
            if ((cloudHandler == null) || (cloudHandler.getThingByUID(devThingUID) == null)) {
                logger.info("RachioDiscovery: New Rachio device discovered: '{}' (id {}), S/N={}, MAC={}", dev.name,
                        dev.id, dev.serialNumber, dev.macAddress);
                logger.debug("  latitude={}, longitude={}", dev.latitude, dev.longitude);
                logger.info("   device status={}, paused/sleep={}, on={}", dev.status, dev.getSleepMode(),
                        dev.getEnabled());
                @SuppressWarnings({ "unchecked", "rawtypes" })
                Map<String, Object> properties = (Map) dev.fillProperties();
                DiscoveryResult discoveryResult = DiscoveryResultBuilder.create(devThingUID).withProperties(properties)
                        .withBridge(bridgeUID).withLabel(dev.getThingName()).build();
                thingDiscovered(discoveryResult);
            } // if (cloudHandler.getThingByUID(dev_thingUID) == null)

            HashMap<String, RachioZone> zoneList = dev.getZones();
            logger.info("RachioDiscovery: Found {} zones for this device.", zoneList.size());
            for (HashMap.Entry<String, RachioZone> ze : zoneList.entrySet()) {
                RachioZone zone = ze.getValue();
                logger.debug("RachioDiscovery: Checking zone with ID '{}'", zone.id);

                // register thing if it not already exists
                ThingUID zoneThingUID = new ThingUID(THING_TYPE_ZONE, bridgeUID, zone.getThingID());
                zone.setUID(devThingUID, zoneThingUID);
                if ((cloudHandler == null) || (cloudHandler.getThingByUID(zoneThingUID) == null)) {
                    logger.info("RachioDiscovery: Zone#{} '{}' (id={}) added, enabled={}", zone.zoneNumber, zone.name,
                            zone.id, zone.getEnabled());

                    if (zone.getEnabled() == OnOffType.ON) {
                        @SuppressWarnings({ "unchecked", "rawtypes" })
                        Map<String, Object> zproperties = (Map) zone.fillProperties();
                        DiscoveryResult zoneDiscoveryResult = DiscoveryResultBuilder.create(zoneThingUID)
                                .withProperties(zproperties).withBridge(bridgeUID)
                                .withLabel(dev.name + "[" + zone.zoneNumber + "]: " + zone.name).build();
                        thingDiscovered(zoneDiscoveryResult);
                    } else {
                        logger.info("RachioDiscovery: Zone#{} '{}' is disabled, skip thing creation", zone.name,
                                zone.id);
                    }
                } // if (cloudHandler.getThingByUID(zoneThingUID) == null)
            } // for (each zone)
        } // for (seach device)
        logger.debug("RachioDiscovery: discovery done.");
    } // startScan()

    @Override
    protected synchronized void stopScan() {
        super.stopScan();
    }

    private Map<String, String> fillProperties(String id) {
        Map<String, String> properties = new HashMap<>();
        properties.put(Thing.PROPERTY_VENDOR, BINDING_VENDOR);
        properties.put(PROPERTY_APIKEY, id);
        properties.put(PROPERTY_EXT_ID, id);
        properties.put(PROPERTY_NAME, "Rachio Cloud Connector");
        return properties;
    }

} // class RachioDiscoveryService
