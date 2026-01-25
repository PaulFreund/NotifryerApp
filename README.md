# NotifryerApp [![Implemented with Codex](https://img.shields.io/badge/Implemented%20with-Codex-6A5ACD?logo=openai&logoColor=white)](https://github.com/openai/codex)

NotifryerApp keeps a live status stream in sync between an Android phone and a Wear OS watch.
Both clients listen to a shared Firebase Cloud Messaging (FCM) topic and render incoming JSON
payloads as ongoing status notifications, quiet background updates, or attention‑grabbing alerts.

The repository contains two Gradle modules:

```
app/   -> Android phone experience (status notification, settings UI)
wear/  -> Wear OS companion (tile + notifications)
```

## Prerequisites

- Android Studio Jellyfish / Koala or newer with JDK 17 installed
- Firebase project with Cloud Messaging enabled
- `google-services.json` for each package:
  - `app/google-services.json` for `com.notifryer.app`
  - `wear/google-services.json` for `com.notifryer.wear`
- Optional but recommended: a Node-RED flow or any backend capable of calling the FCM HTTP v1 API

## Quick Start

1. **Clone & open** the project in Android Studio, let Gradle sync, and verify with `./gradlew tasks`.
2. **Drop in Google configs** for both modules (see prerequisites).
3. **Run the apps**:
   - Phone: select the `app` run configuration and deploy to an Android 13+ device.
   - Wear: pair a Wear OS 4/5 device or emulator, then run the `wear` configuration.
4. Both apps subscribe to the FCM topic `notifryer-status` on first launch. Push a payload to that topic
   (details below) and confirm that the phone notification, Wear tile, and alerts update together.

## Payload Format (Read Me First)

NotifryerApp expects **FCM data messages** whose `payload` key contains a JSON array of event objects.
FCM data values must be strings, so the array itself is stringified. Each element describes one
notification event. The phone and watch share the same schema.

```json
{
  "message": {
    "topic": "notifryer-status",
    "data": {
      "payload": "[{\"timestamp\":1732207200000,\"topic\":\"Front Door\",\"type\":\"ongoing\",\"text\":\"Doorbell idle\",\"timeout\":300,\"tags\":[\"doorbell\"],\"actions\":[{\"name\":\"Remove\",\"url\":\"https://example.com/rest/remove\"}]}]"
    }
  }
}
```

### Event Object Reference

| Field | Required | Type | Notes |
| ----- | :------: | ---- | ----- |
| `topic` | ✓ | String | Identifier for the status. Acts as the notification title and tile headline. Unique per ongoing item. |
| `type` | ✓ | String | One of `ongoing`, `normal`, `important`. Determines priority and channel on both devices. |
| `permanent` |   | Boolean | When `true`, posts the event as an Android ongoing notification (not swipe-dismissible). Defaults to `false`. Clear it via `remove: true` or by updating the same `topic`. |
| `timestamp` | ✓ | Number | Unix epoch millis. If omitted or ≤0 we fall back to `Instant.now()`. |
| `text` | ✓ | String | Body text shown on phone notification and watch. Newlines are supported. |
| `tags` | ✓ | Array<String> | Used for allow/block filtering in settings. Include `"*"` or leave empty to bypass filtering. Comparisons are case-insensitive. |
| `actions` |   | Array<Object> | Optional notification buttons. Each item supplies a `name` and `url` (HTTP/HTTPS) and triggers a GET request without dismissing the notification. |
| `vibrate_pattern` |   | Array<Number> | Millisecond pattern for important alerts. Ignored for silent events. |
| `timeout` |   | Number | Seconds before a missing-status alert fires for `ongoing` topics. Requires `alertOnMissingStatus` enabled. |
| `remove` |   | Boolean | When `true`, dismisses all notifications and stored state for the given `topic`. Other fields are ignored. |

#### Action Objects

Each entry in `actions` adds a button to both the phone and Wear OS notifications. Buttons fire an HTTP GET request and leave the notification visible.

| Field | Required | Type | Notes |
| ----- | :------: | ---- | ----- |
| `name` | ✓ | String | Label shown on the notification button. Keep it short to fit on Wear dialogs. |
| `url` | ✓ | String | Fully qualified HTTP or HTTPS URL invoked with a GET request. Up to three actions per event are rendered. |

You can send multiple events in a single message; each element is processed sequentially.

#### Removal Example

```json
[
  {
    "topic": "Front Door",
    "remove": true
  }
]
```

#### Important Alert Example

```json
[
  {
    "timestamp": 1732210800000,
    "topic": "Front Door",
    "type": "important",
    "text": "Someone rang the doorbell",
    "tags": ["doorbell", "visitor"],
    "vibrate_pattern": [0, 500, 200, 500]
  }
]
```

#### Mixed Batch Example

```json
[
  {
    "timestamp": 1732207200000,
    "topic": "Front Door",
    "type": "ongoing",
    "text": "Doorbell idle",
    "timeout": 300,
    "tags": ["doorbell"],
    "actions": [
      {
        "name": "Remove",
        "url": "https://example.com/rest/remove"
      }
    ]
  },
  {
    "timestamp": 1732207400000,
    "topic": "Front Door",
    "type": "normal",
    "text": "Doorbell ping received",
    "tags": ["doorbell", "heartbeat"]
  }
]
```

### Sending from Node-RED

1. Obtain an OAuth2 access token for the FCM HTTP v1 API using a service account with
   `firebase.messaging.send` permission.
2. POST to `https://fcm.googleapis.com/v1/projects/<project-id>/messages:send` with headers:
   - `Authorization: Bearer <token>`
   - `Content-Type: application/json`
3. Body structure is the same as in the examples above. The only required property outside of the
   payload is `message.topic` (use `notifryer-status` unless you changed the constants).

**Tip:** wrap the event list with `JSON.stringify` inside Node-RED before placing it in `data.payload`.

### Why stringified payloads?

FCM data messages constrain all values to strings. By stringifying the JSON array we keep complex
structures intact (lists, nested objects, numbers). The clients call
`Json.decodeFromString<List<NotificationEvent>>(payload)` to reconstruct the objects.

## App Behavior Highlights

- **Notification Channels**
  - Phone: `ongoing`, `normal`, and `important` channels handle low/high priority behavior.
  - Wear: separate toggles allow muting silent (ongoing + normal) vs important alerts when paired.
- **Action Buttons**
  - Up to three buttons per event appear on both devices when `actions` are provided. Triggered buttons issue a best-effort HTTP GET to the supplied URL and leave the notification in place.
- **Missing Status Alerts**
  - When an `ongoing` event specifies `timeout`, the phone (and optionally the watch) will raise an
    important notification if the topic stops updating before the timeout elapses.
- **Tag Filtering**
  - Configure allow/block lists in the phone app. Tags are case-insensitive; wildcard `*` allows
    everything.
- **Persistence**
  - Both apps use Jetpack DataStore to persist the latest status so notifications and tiles survive
    process death or restarts.
- **Topic Subscription**
  - `MessagingConstants.STATUS_TOPIC` and `WearViewModel.STATUS_TOPIC` default to `notifryer-status`.
    Change both (plus your backend) if you need different broadcast groups.

## Development Tips

- Run `./gradlew :app:assembleDebug :wear:assembleDebug` after placing `google-services.json` files.
- Use the phone settings screen → “Send test notification” to validate parsing locally without FCM.
- When testing real pushes, check `Logcat` for `NotifryerFCM` (phone) or `WearFCM` (wear) if payloads
  don’t appear. Most issues are malformed JSON (missing quotes) or failing tag filters.

## Extending NotifryerApp

- Multiple topics: fork the constants so each deployment subscribes to a dedicated topic.
- Rich data: add fields to `NotificationEvent` and update rendering logic. Unknown JSON attributes are
  ignored thanks to `Json { ignoreUnknownKeys = true }`.
- Custom surfaces: the Wear module already exposes DataStore access; reuse it for complications or tiles.

Happy monitoring! If you improve the payload pipeline, please open a PR so everyone benefits.
