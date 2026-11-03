Local Services Template (ATAK plugin, gRPC)

_________________________________________________________________
PURPOSE AND CAPABILITIES

A starting point for an ATAK plugin whose backend **gRPC services** can run **on the phone
itself** as well as on a remote server. The plugin code that calls a service is the same either
way; only the *connection* changes.

- **Remote:** the plugin talks to the service over the network, as it would to any server.
- **On device:** the same service code runs in a background part of this plugin's APK, and
  the plugin talks to it without touching the network.

The service in this template, "Calc", is a small stand-in so the whole path can be built,
tested and demonstrated. It has one example of each kind of endpoint the old REST calculation API
had: a plain request/answer, a long calculation returning data, and a long calculation returning
a multi-MB image. You replace it with the real services; see
[Part 6](#part-6-plugging-in-the-real-services).

This README assumes you know ATAK plugin development but have **never used gRPC**. Read it top
to bottom the first time.

- [Part 1: gRPC from zero](#part-1-grpc-from-zero)
- [Part 2: How this template works](#part-2-how-this-template-works)
- [Part 3: Following one call end to end](#part-3-following-one-call-end-to-end)
- [Part 4: The calculation examples (REST shapes → gRPC)](#part-4-the-calculation-examples-rest-shapes--grpc)
- [Part 5: The files, one by one](#part-5-the-files-one-by-one)
- [Part 6: Plugging in the real services](#part-6-plugging-in-the-real-services)
- [Part 7: Building, testing, and trying it on a device](#part-7-building-testing-and-trying-it-on-a-device)
- [Part 8: Troubleshooting](#part-8-troubleshooting)
- [Glossary](#glossary)

_________________________________________________________________
STATUS

Template. The service tests pass on a PC (including streaming, cancellation and a 4 MB+ image), and the debug and release APKs
build against the ATAK 5.7.0 CIV SDK with gRPC 1.84.0, using the services as a published library
(`com.example:calc-services:1.0.0` from Maven local). I checked the APK contents: the gRPC
server, Guava and protobuf are bundled; androidx.core and okio are not (ATAK supplies them); gRPC
class names survive the release build. **Not yet run on a device.**

_________________________________________________________________
POINT OF CONTACTS

(Who is developing this)

_________________________________________________________________
PORTS REQUIRED

None for the on-device services: they use Android Binder, not a network port. Remote mode uses
whatever port the remote gRPC services listen on.

_________________________________________________________________
EQUIPMENT REQUIRED

_________________________________________________________________
EQUIPMENT SUPPORTED

Android 7.0 (API 24) or newer, as required by grpc-binder.

_________________________________________________________________
COMPILATION

    ./gradlew -p services test publishToMavenLocal   # test the services, publish com.example:calc-services
    ./gradlew assembleCivDebug          # app/build/outputs/apk/civ/debug/
    ./gradlew assembleCivRelease        # always test a release build too (see Part 8)

The services are a separate build that publishes a library; the plugin depends on that library
and never runs protoc. Publish the services before the first plugin build (Part 5).

_________________________________________________________________
DEVELOPER NOTES

# Part 1: gRPC from zero

## 1.1 The problem gRPC solves

With a REST API you agree on URLs and JSON. For example, "POST `/api/range-bearing` with two
positions returns the range and bearing as JSON". Nothing enforces that agreement. Each side writes
its own model classes and hopes they match, and a typo in a field name shows up only at runtime.

**gRPC replaces the URLs and JSON with functions.** You write the list of functions a service
offers, and the data they take and return, in one file. A tool then *generates* the matching
Java code for both the server and the client. The client calls what looks like an ordinary
method, `rangeBearing(request)`, and gRPC handles sending it to the server and bringing the
answer back.

| REST | gRPC |
|---|---|
| URL + HTTP method (`POST /api/range-bearing`) | a function name (`RangeBearing`) |
| JSON bodies | **protobuf** messages: compact binary, generated classes |
| Retrofit interface you write by hand | client **stub** generated for you |
| Spring `@RestController` | a class extending the generated **ImplBase** |
| HTTP status (404, 400, 500) | gRPC **status code** (`NOT_FOUND`, `INVALID_ARGUMENT`, `INTERNAL`) |
| base URL (`http://10.0.0.5:8081/`) | a **channel** to `host:port` (`10.0.0.5:9090`) |
| Retrofit `Callback` | `StreamObserver` |
| polling for updates | **streaming**: the server pushes updates down an open call |

## 1.2 The `.proto` file: the contract

Everything starts from a `.proto` file (Protocol Buffers). This template's is
`services/src/main/proto/calc.proto`. Shortened:

```proto
syntax = "proto3";
package calc;

option java_package = "com.example.calc.proto";    // Java package for the generated classes
option java_multiple_files = true;                  // one .java file per message

service Calc {                                       // the service: a list of functions ("rpcs")
  rpc RangeBearing (RangeBearingRequest) returns (RangeBearingResult);
  rpc OptimiseRoute (OptimiseRouteRequest) returns (stream OptimiseRouteUpdate);  // "stream", see 1.7
}

message LatLon {                                     // a message: a data structure
  double lat = 1;                                    // type, name, and a field NUMBER
  double lon = 2;
}

message RangeBearingRequest { LatLon from = 1; LatLon to = 2; }   // messages can contain messages
message RangeBearingResult { double range_m = 1; double bearing_deg = 2; }

message OptimiseRouteRequest {
  repeated LatLon points = 1;                        // "repeated" = a list
  int32 iterations = 2;
}
```

What to notice:

- **`service` and `rpc`:** each `rpc` is one function. Each takes **exactly one** message and
  returns one message (or a stream of them).
- **`message`** is like a class with fields.
- **The numbers (`= 1`, `= 2`)** identify each field in the binary data sent between the two
  ends. Never renumber an existing field. That's how old and new versions stay compatible.
- **Types:** `string`, `int32`, `int64`, `bool`, `double`, `bytes`, other messages, and
  `repeated X` for lists.

## 1.3 Code generation: you never write the plumbing

When the **services** are built, the **Gradle protobuf plugin** runs the protobuf compiler
(`protoc`) on each `.proto` file and produces Java source. You never edit or commit the
generated code; it lives under `services/build/generated/` and is published inside the
`com.example:calc-services` library. The plugin gets it from that library, so the plugin's own
build has no protoc step. From `calc.proto` you get:

- **Message classes** (`LatLon`, `RangeBearingRequest`, ...). These are immutable. You build them
  with a builder and read them with getters:
  ```java
  LatLon here = LatLon.newBuilder().setLat(-34.9).setLon(138.6).build();
  double lat = here.getLat();            // unset fields return defaults (0 / ""), never null
  List<LatLon> points = request.getPointsList();
  ```
- **`CalcGrpc`,** a class holding both halves of the service:
  - `CalcGrpc.CalcImplBase`: the **server** base class. You extend it and fill in one method
    per rpc.
  - `CalcGrpc.newStub(channel)`: the **client**. Calling a method on it sends the request.
    There's also `newBlockingStub(channel)`, which waits for the answer; use it only off the
    UI thread (the tests use it).
  - `CalcGrpc.SERVICE_NAME` (`"calc.Calc"`): the service's full name, used in the security
    setup.

**What is *not* generated: the service logic.** `CalcImplBase` is empty: every method just
answers `UNIMPLEMENTED`. The code that actually calculates is the class that extends it
(`CalcService`, 1.5). So:

| To use the services... | You need in `services/` |
|---|---|
| **remotely** only | just the `.proto` files |
| **on the device** as well | the `.proto` files **and** the service implementation classes |

**Importing existing `.proto` files is just copying them into `services/src/main/proto/`.**
The next services build generates the Java, and publishing it hands the result to the plugin.

## 1.4 Protobuf "lite" versus "full"

Protobuf comes in two Java flavours:
- **full:** the one servers normally use;
- **lite:** a smaller, faster version that gRPC recommends for Android. This template
  generates **lite**.

The `.proto` files and the messages sent between the two ends are identical, so a lite client
talks to a full server with no problems. Lite simply leaves out a few "extra" features:

- `JsonFormat` (converting messages to and from JSON);
- descriptors and reflection (`getDescriptor()`, `getAllFields()`);
- `Any` with type registries.

If the service code uses one of these, **it won't compile**. You find out immediately at build
time, not in the field. Fix those spots, or ask the service team to keep them out of the shared
service code.

**`.proto` files that define custom options** are the one case needing a small step. These are
files containing `extend google.protobuf.FieldOptions`, which import
`google/protobuf/descriptor.proto`; SAPIENT's `proto_options.proto` is an example. Lite has no
Java for `descriptor.proto`, so generating those files fails with
`Import "google/protobuf/descriptor.proto" was not found`. Put the options file in
`services/src/main/proto-include/` instead of `src/main/proto/`. Files there can be imported
but get no generated code, and `descriptor.proto` is already provided there. The options are
only annotations and aren't needed at runtime. This was tested with the full SAPIENT BSI Flex 335
v2.0 set: all 63 message classes generated and compiled.

## 1.5 The server side: implementing a service

A service is a class that extends the generated `ImplBase` and overrides one method per rpc.
From `services/src/main/java/com/example/calc/CalcService.java`:

```java
public class CalcService extends CalcGrpc.CalcImplBase {

    @Override
    public void rangeBearing(RangeBearingRequest request, StreamObserver<RangeBearingResult> reply) {
        if (!request.hasFrom() || !request.hasTo()) {
            reply.onError(Status.INVALID_ARGUMENT.withDescription("from and to are required").asRuntimeException());
            return;
        }
        reply.onNext(RangeBearingResult.newBuilder()
                .setRangeM(distanceM(request.getFrom(), request.getTo()))
                .setBearingDeg(bearingDeg(request.getFrom(), request.getTo()))
                .build());     // send the answer...
        reply.onCompleted();   // ...and say "that's all"
    }
}
```

The method doesn't *return* the answer. It is handed a `StreamObserver` (`reply`) and pushes the
answer into it:
- `reply.onNext(answer)` sends the answer, then `reply.onCompleted()` says "that's all";
- **or** `reply.onError(status)` sends an error instead.

It looks odd at first, but it's the same shape for single answers and for streams (1.7), which
is why gRPC uses it.

On the server, Spring registers this class with an `@GrpcService` annotation. That's the only
Spring-specific part, which is why the same class can run on the phone.

## 1.6 The client side: channels, stubs, and observers

**A channel** is a connection to a server. You create it once and reuse it:

```java
ManagedChannel channel = OkHttpChannelBuilder.forTarget("10.0.0.5:9090").usePlaintext().build();
```

**A stub** is the generated client that uses a channel. It's cheap, so make one whenever you like:

```java
CalcGrpc.CalcStub calc = CalcGrpc.newStub(channel);
```

**Calling a method** is like Retrofit's `enqueue`. You pass the request plus a `StreamObserver`,
which is gRPC's `Callback`:

```java
calc.rangeBearing(request, new StreamObserver<RangeBearingResult>() {
    @Override public void onNext(RangeBearingResult reply) { /* the answer arrives here              */ }
    @Override public void onError(Throwable t)             { /* failed - see 1.8 for reading the error */ }
    @Override public void onCompleted()                    { /* the server is done (after onNext)      */ }
});
```

The call returns immediately, and the work happens in the background. In this template, the
observer methods are called **on the UI thread** (`GrpcChannels` sets that up), so they can
update views directly, just like Retrofit callbacks. Don't do heavy work in them.

**Call options** are added to the stub before the call; each returns a new stub:

- `.withDeadlineAfter(5, TimeUnit.SECONDS)`: give up after 5 s with `DEADLINE_EXCEEDED`. This
  is gRPC's timeout. Use one on every call, sized for the job.
- `.withWaitForReady()`: if the server isn't reachable yet, wait for it instead of failing
  straight away. For example, the on-device service may still be starting. Combine it with a
  deadline so it doesn't wait forever. This replaces most "retry until it's up" polling.

**When finished,** call `channel.shutdownNow()`. The plugin does this in `onStop()`.

## 1.7 Streaming: replacing polling

With `returns (stream OptimiseRouteUpdate)`, the server can send **many** replies on one call,
over as long as it likes. The client's `onNext` is called once per message, as each one arrives:

```java
calc.optimiseRoute(request, new StreamObserver<OptimiseRouteUpdate>() {
    @Override public void onNext(OptimiseRouteUpdate u) { /* called for EVERY message: progress, then the result */ }
    @Override public void onError(Throwable t)          { /* the call failed or was cancelled                  */ }
    @Override public void onCompleted()                 { /* the server ended the stream: the job is done      */ }
});
```

On the server, the rpc method calls `reply.onNext(...)` as many times as it likes (a progress
update every so often, then the result), then `reply.onCompleted()`. If you'd otherwise poll an
endpoint for job status or changes, a streaming rpc is usually the better design. Part 4 covers
this in detail.

gRPC also has *client* streaming (the client sends many messages) and *bidirectional*
streaming (both sides do). This template doesn't use them, but the pattern is the same.

## 1.8 Errors: status codes

A failed call reaches `onError`. Read it like this:

```java
Status status = Status.fromThrowable(t);
status.getCode();          // e.g. Status.Code.INVALID_ARGUMENT
status.getDescription();   // e.g. "latitude out of range: 91.0"
```

The codes you'll meet most:

| Code | Means | Usual cause here |
|---|---|---|
| `NOT_FOUND` | the thing asked for doesn't exist | a bad ID |
| `CANCELLED` | the client cancelled the call | the Cancel button (4.2) |
| `INVALID_ARGUMENT` | the request was bad | missing or invalid field |
| `UNAVAILABLE` | couldn't reach the server | wrong `host:port`, server down, local service not started |
| `DEADLINE_EXCEEDED` | no answer before the deadline | server slow or unreachable (with `withWaitForReady`) |
| `PERMISSION_DENIED` | security check refused the call | see [Part 2.4](#24-security-who-may-connect) |
| `INTERNAL` / `UNKNOWN` | the server crashed on this call | a bug in the service; check its logs |
| `UNIMPLEMENTED` | the service has no such rpc | the service isn't registered, or proto versions differ |

# Part 2: How this template works

## 2.1 The big picture

```
            one APK: this plugin
┌──────────── ATAK process ─────────────────────┐        ┌──── "<package>:svc" process ────────┐
│ LocalServicesPlugin (toolbar button + pane)   │        │ LocalServiceHost (Android Service)  │
│                                               │        │   gRPC server (grpc-binder)         │
│ CalcGrpc.newStub(channel).rangeBearing(...)   │ Binder │     └─ CalcService    (services)    │
│   channel = GrpcChannels.local(...)  ─────────┼───────►│                                     │
│          or GrpcChannels.remote("host:port") ─┼──┐     │ NO ATAK classes in this process     │
└───────────────────────────────────────────────┘  │     └─────────────────────────────────────┘
                                                   └── network ──► the real gRPC server
```

**The key idea:** the plugin always uses the generated stub. Choosing local or remote only
means choosing which channel to give the stub:

```java
ManagedChannel channel = local
        ? GrpcChannels.local(pluginContext, MapView.getMapView().getContext())  // this phone
        : GrpcChannels.remote("10.0.0.5:9090");                                 // a server
CalcGrpc.CalcStub calc = CalcGrpc.newStub(channel);                             // same either way
```

## 2.2 Running code outside ATAK: the second process

You already know that ATAK loads a plugin's classes **into ATAK's own process**. That's why ATAK
classes like `MapView` are simply there.

But to Android, the plugin APK is also an ordinary installed app. If its `AndroidManifest.xml`
declares a `<service>`, Android can start that service in a **separate process** that runs the
same APK *without* ATAK:

```xml
<service
    android:name=".localsvc.LocalServiceHost"
    android:process=":svc"
    android:exported="true" />
```

- **`android:process=":svc"`** names that process `com.atakmap.android.localsvctemplate.plugin:svc`.
- **`android:exported="true"`** is required because ATAK is a *different* app, and Android
  otherwise blocks other apps from connecting to a service.

TPC's own `helloworld` and `smsservice` sample plugins do the same thing.

The two processes share **nothing** in memory:

| | Plugin code (ATAK process) | `LocalServiceHost` + services (`:svc` process) |
|---|---|---|
| ATAK classes (`com.atakmap.*`) | available | **not available** (crash: `NoClassDefFoundError`) |
| Libraries the build excludes (OkHttp, okio, androidx core/fragment/lifecycle) | ATAK supplies them | **not available** |
| Everything else in the APK (gRPC, protobuf, your services) | available | available |
| `static` fields and singletons | ATAK's copy | a separate, empty copy |
| If it crashes | ATAK goes down | only `:svc` goes down; ATAK keeps running |

## 2.3 Getting gRPC across processes: grpc-binder

**Binder** is Android's built-in way for processes to talk; every Android service call uses it.
**grpc-binder** is an official gRPC transport that sends gRPC calls over Binder instead of the
network. So:

- **In `:svc`,** `LocalServiceHost` creates a gRPC server with `BinderServerBuilder` and hands
  Android its connection point from `onBind()`.
- **In ATAK,** `GrpcChannels.local()` creates a channel with `BinderChannelBuilder`, pointed at
  `LocalServiceHost`. On first use, the channel asks Android to connect. Android starts the
  `:svc` process if it isn't running.
- Everything else (stubs, observers, status codes, deadlines, streaming) works exactly as it
  does over the network.

When the plugin shuts the local channel down (`onStop`), nothing is connected any more, and
Android stops `LocalServiceHost` and its process.

## 2.4 Security: who may connect

Because the service is `exported`, any app on the phone could *try* to connect. grpc-binder
checks the other side's identity on every connection. **By default it only trusts the same
app on both ends**, and ATAK and this plugin are different apps. So both sides are told who to
trust, using `AllowPackages`:

- **The service** (`LocalServiceHost`) allows ATAK (`BuildConfig.ATAK_PACKAGE_NAME`, i.e.
  `com.atakmap.app.civ`) and the plugin itself:
  ```java
  .servicePolicy(CalcGrpc.SERVICE_NAME, callers)   // one line per service
  ```
- **The client** (`GrpcChannels.local`) only connects to a service belonging to the plugin's
  own package.

A refused connection fails with `PERMISSION_DENIED`.

## 2.5 Threads, briefly

- **In ATAK:** all observer callbacks run on the UI thread (`GrpcChannels` sets
  `.executor(UI_THREAD)`). Update views freely, but keep the work light.
- **In `:svc`:** the service's methods run on gRPC's worker threads, and several calls can run
  at once. Service code must be thread-safe, just as it already had to be on the server.
  `CalcService` keeps no shared state, so it's safe as it is; a service that stores things
  between calls needs thread-safe collections (`ConcurrentHashMap`) or locking.

# Part 3: Following one call end to end

Here is what happens when you press **1. Range/bearing** with "Run services on device" ticked.

1. **The button** calls `rangeBearing()` in `LocalServicesPlugin`. It builds a
   `RangeBearingRequest` from the self marker and the map centre.
2. **`calc()`** returns a stub on `channel()`. The first time, `channel()` creates
   `GrpcChannels.local(...)` and keeps it; later calls reuse it. If you change mode or address,
   it shuts the old channel down and makes a new one.
3. **The call is made:**
   ```java
   calc().withWaitForReady().withDeadlineAfter(5, TimeUnit.SECONDS)
         .rangeBearing(request, observer);
   ```
   It returns immediately.
4. **The channel connects** (first call only). Android starts the `:svc` process, and
   `LocalServiceHost.onCreate()` builds and starts the gRPC server with `CalcService`
   registered. Its `onBind()` hands back the connection point, and both sides run their
   `AllowPackages` checks. `withWaitForReady()` makes the call wait through all of this rather
   than fail.
5. **The request crosses to `:svc`** over Binder. gRPC finds the `RangeBearing` rpc and runs
   `CalcService.rangeBearing(request, reply)` on a worker thread.
6. **The service does its work.** It checks the request, calculates range and bearing, then
   calls `reply.onNext(result)` and `reply.onCompleted()`.
7. **The reply crosses back** to ATAK, and `observer.onNext(result)` runs on the UI thread. The
   pane shows `range/bearing -> 1234 m at 56.7°`.

In **remote** mode, steps 4–7 are the same, except the channel is a network connection to the
real server.

# Part 4: The calculation examples (REST shapes → gRPC)

The old REST API had three kinds of endpoint. `services/src/main/proto/calc.proto` has one
example of each, `CalcService` implements them, and the pane has a button for each. All three
run on the device or remotely, over the same channel.

| | Old REST | gRPC rpc | Pane button |
|---|---|---|---|
| 1 | `POST` with JSON → JSON answer | `RangeBearing`: **unary** (one request, one answer) | 1. Range/bearing |
| 2 | `POST` → job id, poll `GET /job/{id}` → JSON result | `OptimiseRoute`: **server streaming**, progress then the result | 2. Optimise route |
| 3 | `POST` → job id, poll, then `GET` the image bytes (MB) | `RenderHeatmap`: **server streaming**, progress then the image as `bytes` | 3. Render heatmap |

## 4.1 Request, answer (was a plain POST)

The simplest shape, and the one used in Part 1 and Part 3:

```proto
rpc RangeBearing (RangeBearingRequest) returns (RangeBearingResult);
```

The plugin builds the request from the self marker and the map centre, calls it with a 5 s
deadline, and shows the answer in `onNext`. Bad input (latitude 91) comes back as
`INVALID_ARGUMENT`, which is the 400 of gRPC.

## 4.2 Long calculation, small result (was POST + polling)

With REST, a long job needed three things: a job id, a status endpoint, and a timer on the
client polling it. **With gRPC it's one call that stays open until the job is done:**

```proto
rpc OptimiseRoute (OptimiseRouteRequest) returns (stream OptimiseRouteUpdate);

message OptimiseRouteUpdate {
  oneof kind {                       // each message is EITHER progress OR the result
    Progress progress = 1;
    OptimiseRouteResult result = 2;
  }
}
```

- **`oneof`** means each message on the stream carries exactly one of the listed fields. The
  client checks which with `update.hasProgress()` / `update.hasResult()` (or `getKindCase()`).
  One message type can then carry everything the stream needs to send.
- **On the server** (`CalcService.optimiseRoute`), the work runs in the rpc method itself. Every
  so often it sends `onNext(progress)`, and at the end `onNext(result)` then `onCompleted()`.
  The method runs on a gRPC worker thread, so blocking there for a while is fine.
- **On the client** (`LocalServicesPlugin.optimiseRoute`), progress updates the progress bar;
  the result fills the text. The deadline is 2 minutes, not 5 s: **size each deadline for the job**.
- **What replaced the job id:** the call itself. If the job must survive the client
  disconnecting (the phone loses signal for a minute, say), you'd want a job id back: a
  `StartJob` rpc returning an id plus a `WatchJob(id)` stream to reattach. Only do that if you need it.

### Cancelling

REST needed a `DELETE /job/{id}`. In gRPC, the **client cancels the call** and the server finds out:

- **Client:** use a `ClientResponseObserver` instead of a `StreamObserver`. Its extra method
  `beforeStart(call)` hands you the call, and `call.cancel("why", null)` cancels it. The
  observer then gets `onError` with `CANCELLED`. See `LongCall` and the **Cancel** button.
- **Server:** check `isCancelled()` in the loop and stop. A deadline passing or the client
  disconnecting also counts as cancelled, so this one check covers all three:
  ```java
  ServerCallStreamObserver<OptimiseRouteUpdate> out = (ServerCallStreamObserver<OptimiseRouteUpdate>) reply;
  ...
  if (out.isCancelled()) return;   // nobody is listening any more: stop working
  ```
  This matters more on the phone: a calculation nobody is waiting for still drains the battery.

## 4.3 Long calculation, large binary result (was POST + polling + image bytes)

The same as 4.2. The result just carries the image file as `bytes`:

```proto
message HeatmapImage {
  string mime_type = 1;   // "image/png"
  int32 width = 2;
  int32 height = 3;
  bytes data = 4;         // the image file
}

message RenderHeatmapUpdate {
  oneof kind { Progress progress = 1; HeatmapImage result = 2; }
}
```

- `bytes` is protobuf's type for raw binary. In Java it's a `ByteString`:
  `ByteString.copyFrom(png)` on the server, `result.getData().toByteArray()` on the client.
- **The one thing to remember: the 4 MB limit.** By default gRPC refuses to *receive* a message
  over 4 MB (`RESOURCE_EXHAUSTED: ... larger than max`). The limit is on the receiving end, so
  it's the **plugin's channels** that raise it: `GrpcChannels` sets
  `.maxInboundMessageSize(MAX_MESSAGE_BYTES)` (32 MB) on both the local and remote channel.
  Nothing changes on the server. If the real images can be bigger, raise `MAX_MESSAGE_BYTES`.
- **The server's PNG writer** (`Png.java`) uses only `java.util.zip`. On a server you'd use
  `ImageIO`, but `java.awt` and `javax.imageio` **don't exist on Android**. Watch for that in
  service code that should run on the device.
- **If results get much bigger** (tens of MB), send the file in pieces instead: a
  `bytes chunk` field in the `oneof`, sent several times, which the client appends together.
  That keeps memory down and allows cancelling part-way. For a few MB, one message is simpler
  and fine.

## 4.4 What to copy for the real endpoints

| The REST endpoint... | Copy |
|---|---|
| answers straight away | `RangeBearing` (proto) + `rangeBearing()` (plugin) |
| was polled, result is data | `OptimiseRoute`: `oneof { Progress, Result }` stream, `isCancelled()` checks, `LongCall` + Cancel |
| was polled, result is a file/image | `RenderHeatmap`: as above with a `bytes` field, plus `maxInboundMessageSize` on the channels |

`simulate_ms` in the requests is **demo only**. It slows the examples down so the progress bar
has something to show. Leave it out of real protos.

# Part 5: The files, one by one

```
LocalServicesTemplate/
├── build.gradle                  root: the Android Gradle plugin
├── settings.gradle               includes :app only (services: see includeBuild below)
├── services/                     THE SERVICES: their own build, publishes com.example:calc-services
│   ├── settings.gradle           makes services/ a standalone build (the services team's repo)
│   ├── build.gradle              protobuf plugin config (lite), gRPC dependencies, publishing
│   ├── src/main/proto/           the raw .proto files     ← the real services' .proto files go here
│   │   └── calc.proto            the example contract
│   ├── src/main/proto-include/   import-only .proto files (1.4)
│   ├── src/main/java/            the core service code    ← the real service classes go here
│   │   └── .../calc/CalcService.java, Png.java   the example implementation
│   └── src/test/java/            PC tests
│       └── .../calc/CalcServiceTest.java         progress, cancel, 4 MB+ image
└── app/                          THE PLUGIN
    ├── build.gradle              com.example:calc-services, grpc-binder + grpc-okhttp, minSdk 24
    ├── proguard-gradle.txt       keep rules for gRPC and protobuf (release builds)
    └── src/main/
        ├── AndroidManifest.xml   declares LocalServiceHost (process ":svc")
        ├── res/layout/main_layout.xml   demo pane
        └── java/.../plugin/
            ├── LocalServicesPlugin.java     the plugin + demo buttons         (ATAK process)
            ├── GrpcChannels.java            local(...) / remote("host:port")  (ATAK process)
            └── localsvc/
                ├── LocalServiceHost.java    runs the gRPC server              (:svc process)
                └── AllowPackages.java       who may connect                   (both)
```

The generated gRPC code isn't in the tree, and it isn't generated by the plugin's build either.

**How the services reach the plugin.** `services/` stands in for the services team's repo. Its
build generates the lite code from the `.proto` files, compiles it with the service classes, and
publishes the lot as one versioned library, `com.example:calc-services`. The plugin depends on it
like any other library:

```groovy
implementation 'com.example:calc-services:1.0.0'    // app/build.gradle
```

```
services build:  .proto + CalcService ──protoc + javac──► calc-services-1.0.0.jar ──publish──► Maven repo
plugin build:                                Maven repo ──► app (no protoc, no .proto files)
```

- **Where it's published:** `publishToMavenLocal` puts it in `~/.m2`, which the plugin's build
  already searches. That's enough on one machine. For a team, publish to a shared repository
  (Nexus, Artifactory, GitHub Packages) with
  `./gradlew -p services publish -PservicesRepoUrl=... -PservicesRepoUser=... -PservicesRepoPassword=...`,
  and add that repository to `app/build.gradle`.
- **Versions:** bump `version` in `services/build.gradle` on every `.proto` or service change,
  publish, then change the version in `app/build.gradle` to match. Always pin an exact version,
  never `+` or `latest.release`.
- **The protobuf runtime comes with it:** the library's `api` dependency on `grpc-protobuf-lite`
  brings `protobuf-javalite`, which is bundled into the plugin APK. ATAK 5.7.0 doesn't ship a
  Java protobuf runtime (its TAK Protocol code is native), so there's no version to match.
- **Working on both at once:** uncomment `includeBuild('services')` in `settings.gradle` (or
  pass `--include-build services`). Gradle then builds the services from source in place of
  the published library, so edits show up without publishing. Comment it out again before a
  release build, so the plugin is built against a published version.
- **Sources:** a sources jar is published too, including the generated code, so the IDE can show
  `CalcGrpc` and the messages from inside the plugin.

- **`services/build.gradle`** holds the part that makes `.proto` files "just work":
  ```groovy
  protobuf {
      protoc { artifact = "com.google.protobuf:protoc:3.25.9" }          // the protobuf compiler
      plugins { grpc { artifact = "io.grpc:protoc-gen-grpc-java:1.84.0" } } // adds CalcGrpc
      generateProtoTasks {
          all().configureEach { task ->
              task.builtins { java { option 'lite' } }                    // Android-sized messages
              task.plugins { grpc { option 'lite' } }
          }
      }
  }
  ```
  Its dependencies are `api`, so they reach the plugin through the published library. Versions: gRPC
  1.84.0, and `protoc` 3.25.9, which matches the protobuf-javalite that gRPC 1.84 uses. It
  compiles at Java 17, as TAK's plugin signing requires (the services are part of the APK).
- **`CalcService.java`:** see 1.5 and Part 4. It's a stand-in for your real service classes.
- **`CalcServiceTest.java`** starts a **real** gRPC server and client *in memory* (gRPC's
  "in-process" transport) on your PC. It calls the service exactly as the plugin would, including
  the streams and cancelling. It's the fastest way to check service code.
- **`app/build.gradle`** depends on `com.example:calc-services` and adds the two transports:
  - `grpc-binder`: talks to the on-device services;
  - `grpc-okhttp`: talks to remote services over the network.

  It also sets `minSdkVersion 24`, because grpc-binder needs Android 7.0 or newer.
- **`LocalServiceHost.java`** is the Android service in `:svc`. `onCreate()` builds the gRPC
  server (security policy plus services); `onBind()` returns its connection point;
  `onDestroy()` stops it. Add one `.addService(...)` and one `.servicePolicy(...)` line per
  service.
- **`AllowPackages.java`:** see 2.4. It's about 15 lines: it looks up the package name of the
  app on the other end and checks it against a list.
- **`GrpcChannels.java`:** `local(...)` builds a Binder channel to `LocalServiceHost`, and
  `remote("host:port")` builds a network channel. Both deliver callbacks on the UI thread.
  `remote` uses `usePlaintext()` (no encryption); add TLS before using it beyond a lab network.
- **`LocalServicesPlugin.java`** is the stock template plugin plus the demo pane:
  - `channel()` picks the local or remote channel, and `calc()` returns a stub on it;
  - `rangeBearing()` is a normal call; `optimiseRoute()` and `renderHeatmap()` are the
    streaming calls (Part 4), with `LongCall` and `cancelRunning()` for progress and cancelling;
  - `onStop()` shuts the channel down.
- **`proguard-gradle.txt`:** release builds of ATAK plugins rename classes. gRPC looks some of
  its own classes up by name, and protobuf-lite reads message fields by name, so both get keep
  rules.

# Part 6: Plugging in the real services

1. **Copy the `.proto` files** into `services/src/main/proto/`. If they `import` each other,
   keep their folder structure, so that `import "common/types.proto";` finds
   `src/main/proto/common/types.proto`.
2. **Build** (`./gradlew -p services compileJava`). Fix any errors in the `.proto` files
   themselves first. If you get `Import "google/protobuf/descriptor.proto" was not found`, move
   the file that defines the custom options into `src/main/proto-include/` (see 1.4).
   Then **publish** (`./gradlew -p services publishToMavenLocal`). That's all remote mode needs:
   you can now call the real server from the plugin (step 5).

   If the services team builds and publishes this library from their own repo, these first
   steps happen on their side: you only set the version in `app/build.gradle`.
3. **For on-device mode, copy the core service classes** (the ones that
   `extend XxxGrpc.XxxImplBase`, and what they use) into `services/src/main/java/` as source. Then:
   - **Remove Spring:** `@GrpcService`, `@Service`, `@Autowired`, `@Value`, and anything that
     imports `org.springframework`.
   - **Pass dependencies in through the constructor.** Where Spring used to inject things, the
     class now receives them as `new XxxService(repo, config)`.
   - **Replace databases and other servers** with phone versions behind the same interface: in
     memory first, SQLite if data must survive restarts.
   - **Fix any full-protobuf-only code** (see 1.4); the compiler points to it.
   - **Keep to Java 17** (no Java 21 features such as pattern-matching `switch` or
     `List.getFirst()`), and avoid Java APIs Android 7.0 lacks (`java.awt`, `javax.imageio`,
     `String.isBlank()`, `List.of()`).
4. **Register each service** in `LocalServiceHost.onCreate()`:
   ```java
   .servicePolicy(TracksGrpc.SERVICE_NAME, callers)
   .addService(new TracksService(new InMemoryTrackStore()))
   ```
5. **Call it from the plugin:** `TracksGrpc.newStub(channel)`. Reuse the same channel; one
   channel serves every service on that server.
6. **Write a PC test** by copying `CalcServiceTest`.
7. **Delete the stand-ins:** `calc.proto`, `CalcService`, `Png`, `CalcServiceTest`, their
   `LocalServiceHost` lines, and the demo buttons.
8. **Rename the library:** change `group`, `artifactId` and `rootProject.name` (in
   `services/settings.gradle`) from `com.example` / `calc-services` to your own, publish, and
   update the dependency in `app/build.gradle`.

After that, every services change is: bump `version`, publish, update the version in
`app/build.gradle`.

**Signs a service shouldn't run on the phone yet** (leave it remote-only):
- it needs another server (a database server, a message broker);
- it runs Python or native tools;
- it needs a GPU or a lot of memory.

# Part 7: Building, testing, and trying it on a device

```bash
./gradlew -p services test        # service tests on your PC
./gradlew -p services publishToMavenLocal   # publish the services library for the plugin
./gradlew assembleCivDebug        # APK: app/build/outputs/apk/civ/debug/
./gradlew assembleCivRelease      # release APK - test this before fielding
```

On a device:

1. Install ATAK CIV 5.7.0 and the debug APK, and load the plugin from ATAK's plugin manager.
2. Open the pane (toolbar button). "Run services on device" is ticked.
3. Press the three calculation buttons. **Optimise route** and **Render heatmap** show a
   progress bar, then the result (the heatmap appears below the text). Press **Cancel
   calculation** part-way: the text shows `CANCELLED` and the service stops working.
4. Check the second process and its log:
   ```bash
   adb shell ps -A | grep ':svc'                 # appears after the first local call
   adb logcat -s LocalServiceHost                # "local gRPC services started"
   ```
5. Untick the box and enter a real server's `host:port` to run the same buttons remotely.

**Debugging the service code:** in Android Studio, choose *Run → Attach Debugger to Android
Process* and pick `com.atakmap.android.localsvctemplate.plugin:svc`. Breakpoints in
`CalcService` then work as usual.

# Part 8: Troubleshooting

| You see | Likely cause / fix |
|---|---|
| `Could not find com.example:calc-services:x.y.z` | That version hasn't been published where the plugin looks. Run `./gradlew -p services publishToMavenLocal`, or check the version in `app/build.gradle` matches `services/build.gradle` (Part 5). |
| Service changes don't show up in the plugin | The plugin uses the published library, not `services/` source. Bump the version and publish, or use `includeBuild('services')` while developing (Part 5). |
| `PERMISSION_DENIED` on local calls | A security check refused the connection (2.4). For a non-CIV ATAK build, add its package name where `LocalServiceHost` creates `AllowPackages`. |
| `UNAVAILABLE` or `DEADLINE_EXCEEDED` on local calls | The service didn't start. Check `adb logcat -s LocalServiceHost` for errors, and that the `<service>` entry is in `AndroidManifest.xml`. |
| `UNAVAILABLE` on remote calls | Wrong `host:port`, the server is down, or the network blocks the port. |
| `UNIMPLEMENTED` | The service isn't registered (`addService` missing), or the plugin and the service were built from different `.proto` versions (check the `calc-services` version against the server's). |
| `NoClassDefFoundError` in the `:svc` process | Service code used an ATAK class or an excluded library (2.2). |
| Works in debug, fails in release | A ProGuard keep rule is missing for a library that looks classes up by name; add it in `app/proguard-gradle.txt`. |
| Build error in generated code or in a service class mentioning descriptors, `JsonFormat` or `Any` | Full-protobuf-only features (1.4). |
| `RESOURCE_EXHAUSTED: ... larger than max` | A reply is over the channel's size limit. Raise `MAX_MESSAGE_BYTES` in `GrpcChannels` (4.3). |
| A long calculation fails with `DEADLINE_EXCEEDED` | The deadline is shorter than the job. Size it for the job (4.2). |
| Callbacks freeze the pane | Heavy work in `onNext`. Callbacks run on the UI thread, so move the work to a background thread. |

# Glossary

- **gRPC:** a way to call functions on another program as if they were local. It uses protobuf
  for the data and HTTP/2 (or, here, Binder) to carry it.
- **Protocol Buffers (protobuf):** the language of `.proto` files, and the compact binary format
  the data travels in.
- **`.proto` file:** defines services (functions) and messages (data). It's the contract both
  sides are generated from.
- **`protoc`:** the protobuf compiler. It turns `.proto` into Java; the Gradle plugin runs it
  for you.
- **Message:** a generated, immutable data class, built with `newBuilder()...build()`.
- **rpc:** one function in a service.
- **ImplBase:** the generated server base class your service extends.
- **Stub:** the generated client object you call methods on.
- **Channel:** a connection to a server. Create it once, reuse it, and shut it down when done.
- **StreamObserver:** receives results: `onNext` (a result), `onError` (a failure),
  `onCompleted` (finished).
- **Streaming rpc:** a call where one side sends many messages over time.
- **`oneof`:** a set of fields of which each message carries exactly one; used to send
  progress, info and results down one stream.
- **`bytes` / `ByteString`:** protobuf's raw binary type and its Java class.
- **Cancel:** the client ending a call early (`ClientCallStreamObserver.cancel`); the server
  sees `isCancelled()`.
- **Status / status code:** gRPC's error information (`NOT_FOUND`, `UNAVAILABLE`, ...).
- **Deadline:** gRPC's timeout (`withDeadlineAfter`).
- **Wait-for-ready:** wait for an unreachable server instead of failing immediately
  (`withWaitForReady`).
- **Binder:** Android's built-in way for processes to talk to each other.
- **grpc-binder:** gRPC carried over Binder, used for the on-device services.
- **grpc-okhttp:** gRPC carried over the network, used for remote services.
- **Protobuf lite:** the smaller protobuf flavour recommended for Android (1.4).
- **`:svc` process:** the separate process, outside ATAK, where the on-device services run.

**Changes from the stock `samples/plugintemplate`:**
- package renamed to `com.atakmap.android.localsvctemplate.plugin`;
- `compileSdk 34` (36 fails to configure on this machine; 34 matches the other plugins here);
- `minSdkVersion 24`;
- `services` build added (standalone; publishes `com.example:calc-services`), and the plugin
  depends on that published library;
- `testImplementation junit` added (the stock template's own test doesn't compile without it);
- gRPC and protobuf keep rules added to `app/proguard-gradle.txt`.
