
# Rachio Sprinkler Binding

_Release 2.5.6pre1_

This binding allows to retrieve status information from Rachio Sprinklers and control some functions like run zones, stop watering etc. It uses the Rachio Cloud API, so you need an account and an apikey. To receive events from the Rachio cloud service e.g. start/stop zones & skip watering, you need to have connected your OpenHAB installation to MyOpenHAB.org. This is used to proxy events from Rachio back to your OpenHAB instance.

## Supported Things

|Thing|Description|
|:---|:---|
|cloud|Each Rachio account is represented by a cloud thing. The binding supports multiple accounts at the same time.|
|device|Each sprinkler controller is represented by a device thing, which links to the cloud thing|
|zone|Each zone for each controller creates a zone thing, which links to the device thing (and indirectly to the bridge thing)|

## Discovery

The device setup is read from the Rachio online service, when a Rachio Cloud Connector thing is configured, and therefore it shares the same items as the Smartphone and Web Apps, so there is no special setup required. In fact all Apps (including this binding) control the same device. The binding implements monitoring and control functions, but no configuration etc. To change configuration you could use the Rachio smartphone app or website.

As a result the following things are created
- 1 cloud connection per account
- 1 device for each controller
- n zones for each zone on any controller

Example: 2 controllers with 8 zones each under the same account creates 19 things (1 x bridge, 2 x device, 16 x zone). 

## Thing Configuration

### Rachio Cloud Connector thing - represents a Rachio Cloud account

|Parameter|Description|
|:---|:---|
|apikey|This is a token required to access the Rachio Cloud account. Go to [Rachio Web App](https://rachio.com->login), click on Account Settings in the left navigation. At the bottom you'll find a link "Get API key". Copy the copy and post it to the bridge configuration: apikey=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxx.|
|pollingInterval|The number of seconds between polling the Rachio online service. Usually something like 10 minutes should be enough to have a regular status update when the interfaces is configured. If you don't want/can use events a smaller delay might be interesting to get quicker responses on running zones etc. *Important: Please make sure to use an interval > 90sec. Rachio has a reshhold for the number of API calls per day: 1700. This means if you are accessing the API for more than once in a minute your account gets blocked for the rest of the day.*|
|defaultRuntime|You could run zones in 2 different ways: <ol><li>Just by pushing the button in your UI. The zone will start watering for  <defaultRuntime> seconds.</li><li>Setting the zone's channel runTime to N seconds and then starting the zone. This will start the zone for N seconds.</li></ol>Usually this variant required a OH rule setting the runTime and then sending a ON to the run channel.|
|callbackUrl| https://username:password@home.myopenhab.org/rachio/webhook where username and password should be set to your MyOpenHab username and password (url encode the @ symbol to %40 in your username) <br/>The Rachio Cloud sends events when activity occurs e.g. zone turns off. To recieve these events you must have your openHAB connected to MyOpenHAB.org which enables proxying the events to your local openHAB instance.<br/> You must enable notifications in Rachio. To do this go to the Rachio Web App-&gt;Accounts Settings-&gt;Notifications <br/>|
|clearAllCallbacks|The binding dynamically registers itself with Rachio online. It also supports multiple applications registered to receive events, e.g. a 2nd OH device with the binding providing the same functionality. If for any reason your device setup changes (e.g. new ip address) you need to clear the registered URL once to avoid the old one still receiving events.|
<hr/>

### Rachio Sprinkler Controller thing - represents a single Rachio controller

The are no additional configuration options on the device level.

| Channel |Description|
|:--|:--|
|number|Zone number as assigned by the controller (zone 1..16)|
|name|Name of the zone as configured in the App.|
|enabled|ON: zone is enabled (ready to run), OFF: zone is disabled.|
|run|If this channel received ON the zone starts watering. If runTime is = 0 the defaultRuntime will be used.|
|runTime|Number of seconds to run the zone when run receives ON command|
|runTotal|Total number of seconds the zone was watering (as returned by the cloud service).|
|imageUrl|URL to the zone picture as configured in the App. Rachio supplies default pictures if no image was created. This can be used e.g. in a habPanel to show the zione picture and display the zone name.|
|event|This channel receives a JSON-formatted message on each event received from the Rachio Cloud.|

### Rachio Sprinkler Zone thing - represents one zone of a controller

The are no additional configuration options on the zone level.

## Full Example

conf/things/rachio.things

```
Bridge rachio:cloud:1 [ apikey="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxx", pollingInterval=180, defaultRuntime=120, callbackUrl="https://username:password@home.myopenhab.org/rachio/webhook", clearAllCallbacks=true ]
{
}
```