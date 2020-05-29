/**
 * Copyright (c) 2010-2018 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * @author Markus Michels (markus7017) - Initial contribution
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.rachio.internal;

import static org.openhab.binding.rachio.RachioBindingConstants.*;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandlerFactory;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.eclipse.smarthome.core.thing.binding.ThingHandlerFactory;
import org.openhab.binding.rachio.handler.RachioBridgeHandler;
import org.openhab.binding.rachio.handler.RachioDeviceHandler;
import org.openhab.binding.rachio.handler.RachioZoneHandler;
import org.openhab.binding.rachio.internal.api.RachioEvent;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link RachioHandlerFactory} is responsible for creating things and thing
 * handlers.
 *
 * @author Markus Michels (markus7017) - Initial contribution
 */
@Component(service = { ThingHandlerFactory.class,
        RachioHandlerFactory.class }, immediate = true, configurationPid = "binding." + BINDING_ID)
public class RachioHandlerFactory extends BaseThingHandlerFactory {

    public class RachioBridge {
        RachioBridgeHandler cloudHandler;
        ThingUID uid;
    }

    private final Logger logger = LoggerFactory.getLogger(RachioHandlerFactory.class);
    private final Map<ThingUID, ServiceRegistration<?>> discoveryServiceReg = new HashMap<>();
    private final HashMap<String, RachioBridge> bridgeList;
    private final RachioConfiguration bindingConfig = new RachioConfiguration();

    /**
     * OSGi activation callback.
     *
     * @param config Service config.
     */
    @Activate
    protected void activate(ComponentContext componentContext, Map<String, Object> configProperties) {
        super.activate(componentContext);
        logger.debug("RachioBridge: Activate, configurarion (services/rachio.cfg):");
        bindingConfig.updateConfig(configProperties);
    }

    public RachioHandlerFactory() {
        logger.debug("RachioHandlerFactory: Initialized Rachio Thing handler.");
        bridgeList = new HashMap<String, RachioBridge>();
    }

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SUPPORTED_THING_TYPES_UIDS.contains(thingTypeUID);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        ThingTypeUID thingTypeUID = thing.getThingTypeUID();

        if (thingTypeUID.equals(THING_TYPE_CLOUD)) {
            return createBridge((Bridge) thing);
        }
        if (THING_TYPE_DEVICE.equals(thingTypeUID)) {
            return new RachioDeviceHandler(thing);
        }
        if (THING_TYPE_ZONE.equals(thingTypeUID)) {
            return new RachioZoneHandler(thing);
        }

        return null;
    }

    @Override
    protected void removeHandler(final ThingHandler thingHandler) {
        logger.debug("RachioHandlerFactory:: Removing Rachio Cloud handler");
        if (thingHandler instanceof RachioBridgeHandler) {
            RachioBridgeHandler bridgeHandler = (RachioBridgeHandler) thingHandler;
            //unregisterDiscoveryService(bridgeHandler);
            bridgeHandler.shutdown();
        }
        if (thingHandler instanceof RachioDeviceHandler) {
            RachioDeviceHandler deviceHandler = (RachioDeviceHandler) thingHandler;
            deviceHandler.shutdown();
        }
        if (thingHandler instanceof RachioZoneHandler) {
            RachioZoneHandler zoneHandler = (RachioZoneHandler) thingHandler;
            zoneHandler.shutdown();
        }
    }

    /**
     * Called from the webhook servlet. event.externalId is used to route the event to the corresponding bridge handler
     *
     * @param event
     */
    public boolean webHookEvent(String ipAddress, RachioEvent event) {
        try {
            logger.trace("RachioEvent: Event for device '{}' received", event.deviceId);
            for (HashMap.Entry<String, RachioBridge> be : bridgeList.entrySet()) {
                RachioBridge bridge = be.getValue();
                logger.trace("RachioEvent: Check for externalId: '{}' / '{}'", event.externalId,
                        bridge.cloudHandler.getExternalId());
                if (bridge.cloudHandler.getExternalId().equals(event.externalId)) {
                    return bridge.cloudHandler.webHookEvent(event);
                }
            }
            logger.info("RachioEvent: Unauthorized webhook event (wrong externalId: '{}')", event.externalId);
            return false;
        } catch (Exception e) {
            logger.warn("RachioEvent: Unable to process event: {}", e.getMessage());

        }
        logger.debug("RachioEvent: Unable to route event to bridge, externalId='{}', deviceId='{}'", event.externalId,
                event.deviceId);
        return false;
    } // webHookEvent()


    private RachioBridgeHandler createBridge(Bridge bridgeThing) {
        try {
            RachioBridge bridge = new RachioBridge();
            bridge.uid = bridgeThing.getUID();
            bridge.cloudHandler = new RachioBridgeHandler(bridgeThing);
            bridge.cloudHandler.setConfiguration(bindingConfig);
            bridgeList.put(bridge.uid.toString(), bridge);

            //registerDiscoveryService(bridge.cloudHandler);
            return bridge.cloudHandler;
        } catch (Exception e) {
            logger.warn("RachioFactory: Unable to create bridge thing: {}: ", e.getMessage());
        }
        return null;
    }

}
