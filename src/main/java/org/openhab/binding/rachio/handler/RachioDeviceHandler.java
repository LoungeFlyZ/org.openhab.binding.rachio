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
package org.openhab.binding.rachio.handler;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.ThingStatusInfo;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.eclipse.smarthome.core.types.State;
import org.openhab.binding.rachio.RachioBindingConstants;
import org.openhab.binding.rachio.internal.api.RachioApiException;
import org.openhab.binding.rachio.internal.api.RachioDevice;
import org.openhab.binding.rachio.internal.api.RachioEvent;
import org.openhab.binding.rachio.internal.api.RachioZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link RachioDeviceHandler} is responsible for handling commands, which are
 * sent to one of the device channels.
 *
 * @author Markus Michels (markus7017) - Initial contribution
 */

@NonNullByDefault
public class RachioDeviceHandler extends BaseThingHandler implements RachioStatusListener {
    private final Logger logger = LoggerFactory.getLogger(RachioDeviceHandler.class);

    @Nullable
    Bridge bridge;
    @Nullable
    RachioBridgeHandler cloudHandler;
    @Nullable
    RachioDevice dev;
    private Map<String, State> channelData = new HashMap<>();

    public RachioDeviceHandler(Thing thing) {
        super(thing);
    }
    
    @Override
    public void initialize() {
        logger.debug("RachioDevice: Initializing Rachio device '{}'.", getThing().getUID().getAsString());

        String errorMessage = "";
        try {
            this.bridge = getBridge();
            Bridge bridge = this.bridge;
            

            if (bridge != null) {
                ThingHandler handler = bridge.getHandler();
                if ((handler != null) && (handler instanceof RachioBridgeHandler)) {
                    cloudHandler = (RachioBridgeHandler) handler;
                    dev = cloudHandler.getDevByUID(this.getThing().getUID());
                    if (dev != null) {
                        dev.setThingHandler(this);
                        cloudHandler.registerStatusListener(this);
                        cloudHandler.registerWebHook(dev.id);
                        if (bridge.getStatus() != ThingStatus.ONLINE) {
                            logger.debug("Rachio: Bridge is offline!");
                            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
                        } else {
                            updateProperties();
                            updateStatus(dev.getStatus());
                            logger.debug("RachioDevice: Rachio device '{}' initialized.",
                                    getThing().getUID().getAsString());
                            return;
                        }
                    }
                }
            }
            errorMessage = "Initialisation failed";
        } catch (RachioApiException e) {
            errorMessage = e.toString();
        } catch (Throwable e) {
            if (e.getMessage() != null) {
                errorMessage = e.getMessage();
            }
        } finally {
            if (!errorMessage.isEmpty()) {
                logger.warn("RachioBridge: {}", errorMessage);
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, errorMessage);
            }
        }
    } // initialize()

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        String channel = channelUID.getId();
        logger.debug("RachioDevice.handleCommand {} for {}", command.toString(), channel);

        if ((cloudHandler == null) || (dev == null)) {
            logger.debug("RachioDevice: Cloud handler or device not initialized!");
            return;
        }

        String errorMessage = "";
        try {
            if (command == RefreshType.REFRESH) {
                // cloudHandler.refreshDeviceStatus();
                postChannelData();
            } else if (channel.equals(RachioBindingConstants.CHANNEL_DEVICE_ACTIVE)) {
                if (command instanceof OnOffType) {
                    if (command == OnOffType.OFF) {
                        logger.info("RachioDevice: Pause device '{}' (disable watering, schedules etc.)", dev.name);
                        cloudHandler.disableDevice(dev.id);
                    } else {
                        logger.info("RachioDevice: Resume device '{}' (enable watering, schedules etc.)", dev.name);
                        cloudHandler.enableDevice(dev.id);
                    }
                } else {
                    logger.debug("RachioDevice: command value is no OnOffType: {}", command);
                }
            } else if (channel.equals(RachioBindingConstants.CHANNEL_DEVICE_RUN_TIME)) {
                if (command instanceof DecimalType) {
                    int runtime = ((DecimalType) command).intValue();
                    logger.info("RachioDevice: Default Runtime for zones set to {} sec", runtime);
                    dev.setRunTime(runtime);
                } else {
                    logger.debug("RachioDevice: command value is no DecimalType: {}", command);
                }
            } else if (channel.equals(RachioBindingConstants.CHANNEL_DEVICE_RUN_ZONES)) {
                if (command instanceof StringType) {
                    logger.info("RachioDevice: Run multiple zones: '{}' ('' = ALL)", command.toString());
                    dev.setRunZones(command.toString());
                } else {
                    logger.debug("RachioDevice: command value is no StringType: {}", command);
                }
            } else if (channel.equals(RachioBindingConstants.CHANNEL_DEVICE_RUN)) {
                if (command == OnOffType.ON) {
                    logger.info("RachioDevice: START watering zones '{}' ('' = ALL)", dev.getRunZones());
                    cloudHandler.runMultipleZones(dev.getAllRunZonesJson(cloudHandler.getDefaultRuntime()));
                }
            } else if (channel.equals(RachioBindingConstants.CHANNEL_DEVICE_STOP)) {
                if (command == OnOffType.ON) {
                    logger.info("RachioDevice: STOP watering for device '{}'", dev.name);
                    cloudHandler.stopWatering(dev.id);
                    updateState(RachioBindingConstants.CHANNEL_DEVICE_STOP, OnOffType.OFF);
                }
            } else if (channel.equals(RachioBindingConstants.CHANNEL_DEVICE_RAIN_DELAY)) {
                if (command instanceof DecimalType) {
                    logger.info("RachioDevice: Start rain delay cycle for {} sec", command.toString());
                    dev.setRainDelayTime(((DecimalType) command).intValue());
                    cloudHandler.startRainDelay(dev.id, ((DecimalType) command).intValue());
                } else {
                    logger.debug("RachioDevice: command value is no DecimalType: {}", command);
                }
            }
        } catch (RachioApiException e) {
            errorMessage = e.toString();
        } catch (Throwable e) {
            errorMessage = e.getMessage();
        } finally {
            if (!errorMessage.isEmpty()) {
                logger.warn("RachioDevice.handleCommand: {}", errorMessage);
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, errorMessage);
            }
        }
    } // handleCommand()

    
    private void postChannelData() {
        if (dev != null) {
            logger.debug("RachioDevice: Updating  status");
            updateChannel(RachioBindingConstants.CHANNEL_DEVICE_NAME, new StringType(dev.getThingName()));
            updateChannel(RachioBindingConstants.CHANNEL_DEVICE_ONLINE, dev.getOnline());
            updateChannel(RachioBindingConstants.CHANNEL_DEVICE_ACTIVE, dev.getEnabled());
            updateChannel(RachioBindingConstants.CHANNEL_DEVICE_PAUSED, dev.getSleepMode());
            updateChannel(RachioBindingConstants.CHANNEL_DEVICE_STOP, OnOffType.OFF);
            updateChannel(RachioBindingConstants.CHANNEL_DEVICE_RUN_ZONES, new StringType(dev.getRunZones()));
            updateChannel(RachioBindingConstants.CHANNEL_DEVICE_RUN_TIME, new DecimalType(dev.getRunTime()));
            updateChannel(RachioBindingConstants.CHANNEL_DEVICE_RAIN_DELAY, new DecimalType(dev.rainDelay));
            updateChannel(RachioBindingConstants.CHANNEL_DEVICE_EVENT, new StringType(dev.getEvent()));
            updateChannel(RachioBindingConstants.CHANNEL_DEVICE_LATITUDE, new DecimalType(dev.longitude));
            updateChannel(RachioBindingConstants.CHANNEL_DEVICE_LONGITUDE, new DecimalType(dev.latitude));
            updateChannel(RachioBindingConstants.CHANNEL_DEVICE_SCHEDULE, new StringType(dev.scheduleName));
        }
    }

    @SuppressWarnings({ "null", "unused" })
    private boolean updateChannel(String channelName, State newValue) {
        State currentValue = channelData.get(channelName);
        if ((currentValue != null) && currentValue.equals(newValue)) {
            // no update required
            return false;
        }

        if (currentValue == null) {
            // new value -> update
            channelData.put(channelName, newValue);
        } else {
            // value changed -> update
            channelData.replace(channelName, newValue);

        }

        updateState(channelName, newValue);
        return true;
    }

    
    @Override
    public boolean onThingStateChangedl(@Nullable RachioDevice updatedDev, @Nullable RachioZone updatedZone) {
        if ((updatedDev != null) && (dev != null) && dev.id.equals(updatedDev.id)) {
            logger.debug("RachioDevice: Update for device '{}' received.", dev.id);
            dev.update(updatedDev);
            postChannelData();
            updateStatus(dev.getStatus());
            return true;
        }
        return false;
    }

    
    @Override
    public void bridgeStatusChanged(ThingStatusInfo bridgeStatusInfo) {
        super.bridgeStatusChanged(bridgeStatusInfo);

        logger.debug("RachioDeviceHandler: Bridge Status changed to {}", bridgeStatusInfo.getStatus());
        if (bridgeStatusInfo.getStatus() == ThingStatus.ONLINE) {
            updateProperties();
            postChannelData();
            updateStatus(dev.getStatus());
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
        }
    } // bridgeStatusChanged()

    public void shutdown() {
        dev.setStatus("OFFLINE");
        updateStatus(ThingStatus.OFFLINE);
    }

    
    public boolean webhookEvent(RachioEvent event) {
        boolean update = true; // 1=event processed, 2=processed + force refresh, 0=unhandled event

        try {
            // dev.setEvent(event);
            String etype = event.type;
            if (etype.equals("ZONE_STATUS")) {
                RachioZone zone = dev.getZoneByNumber(event.zoneRunStatus.zoneNumber);
                if ((zone != null) && (zone.getThingHandler() != null)) {
                    return zone.getThingHandler().webhookEvent(event);
                }
            } else if (event.subType.equals("ZONE_DELTA")) {
                RachioZone zone = dev.getZoneById(event.zoneId);
                if ((zone != null) && (zone.getThingHandler() != null)) {
                    return zone.getThingHandler().webhookEvent(event);
                }
            } else if (etype.equals("DEVICE_STATUS")) {
                // sub types:
                // COLD_REBOOT, ONLINE, OFFLINE, OFFLINE_NOTIFICATION, SLEEP_MODE_ON, SLEEP_MODE_OFF, BROWNOUT_VALVE
                // RAIN_SENSOR_DETECTION_ON, RAIN_SENSOR_DETECTION_OFF, RAIN_DELAY_ON, RAIN_DELAY_OFF
                logger.info("Rachio device {} ('{}') changed to status '{}'.", dev.name, dev.id, event.subType);
                if (event.subType.equals("COLD_REBOOT")) {
                    logger.info("Rachio device {} (id '{}') was restarted, ip={}/{}, gw={}, dns={}/{}, wifi rssi={}.",
                            dev.name, dev.id, dev.network.ip, dev.network.nm, dev.network.gw, dev.network.dns1,
                            dev.network.dns2, dev.network.rssi);
                    dev.setNetwork(event.network);
                } else if (event.subType.equals("ONLINE")) {
                    logger.info("Rachio device {} ('{}') is now ONLINE.", dev.name, dev.id);
                    dev.setStatus(event.subType);
                } else if (event.subType.equals("OFFLINE") || event.subType.equals("OFFLINE_NOTIFICATION")) {
                    logger.info("Rachio device {} ('{}') is now OFFLINE (subType = '{}').", dev.name, dev.id,
                            event.subType);
                    dev.setStatus(event.subType);
                } else if (event.subType.equals("SLEEP_MODE_ON")) {
                    logger.info("Rachio device {} ('{}') is now in sleep mode.", dev.name, dev.id);
                    dev.setSleepMode(event.subType);
                } else if (event.subType.equals("SLEEP_MODE_OFF")) {
                    logger.info("Rachio device {} ('{}') was resumed (exit from sleep mode).", dev.name, dev.id);
                    dev.setSleepMode(event.subType);
                } else if (event.subType.equals("RAIN_DELAY_ON")) {
                    logger.info("Rachio device {} ('{}') reporterd a rain delay ON.", dev.name, dev.id);
                    update = false; // details missing
                } else if (event.subType.equals("RAIN_DELAY_OFF")) {
                    logger.info("Rachio device {} ('{}') reporterd a rain delay OFF.", dev.name, dev.id);
                    update = false; // details missing
                } else if (event.subType.equals("RAIN_SENSOR_DETECTION_ON")) {
                    logger.info("Rachio device {} ('{}') reporterd a rain sensor ON.", dev.name, dev.id);
                    update = false; // details missing
                } else if (event.subType.equals("RAIN_SENSOR_DETECTION_ON")) {
                    logger.info("Rachio device {} ('{}') reporterd a rain sensor OFF.", dev.name, dev.id);
                    update = false; // details missing
                } else {
                    update = false; // details missing
                }
            } else if (event.type.equals("SCHEDULE_STATUS")) {
                logger.info("RachioDevice '{}' for device '{}', schedule='{}': {} (start={}, end={}, duration={}min)",
                        event.subType, dev.name, event.scheduleName, event.summary, event.startTime, event.endTime,
                        event.durationInMinutes);
                // no action -> just post event message to OH
            } else {
                update = false; // unknown event
            }

            if (update) {
                postChannelData();
                return true;
            }
            logger.debug("RachioDevice: Unhandled event '{}.{}' for device '{}' ({}): {}", event.type, event.subType,
                    dev.name, dev.id, event.summary);
            return false;
        } catch (Throwable e) {
            logger.warn("RachioDevice: Unable to process '{}.{}' - {}: {}", event.type, event.subType, event.summary,
                    e.getMessage());
            return false;
        }

    } // webhookEvent

    
    private void updateProperties() {
        if (dev != null) {
            logger.trace("Updating Rachio sprinkler properties");
            updateProperties(dev.fillProperties());
        }
    } // updateProperties()
} // class
