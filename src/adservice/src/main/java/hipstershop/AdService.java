/*
 * Copyright 2018, Google LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package hipstershop;

import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import hipstershop.Demo.Ad;
import hipstershop.Demo.AdRequest;
import hipstershop.Demo.AdResponse;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.services.*;
import io.grpc.stub.StreamObserver;
import io.opencensus.common.Duration;
import io.opencensus.contrib.grpc.metrics.RpcViews;
import io.opencensus.exporter.metrics.ocagent.OcAgentMetricsExporter;
import io.opencensus.exporter.metrics.ocagent.OcAgentMetricsExporterConfiguration;
import io.opencensus.exporter.trace.ocagent.OcAgentTraceExporter;
import io.opencensus.exporter.trace.ocagent.OcAgentTraceExporterConfiguration;
import io.opencensus.trace.AttributeValue;
import io.opencensus.trace.Span;
import io.opencensus.trace.Tracer;
import io.opencensus.trace.Tracing;
import io.opencensus.common.Scope;
import io.opencensus.trace.config.TraceConfig;
import io.opencensus.trace.config.TraceParams;
import io.opencensus.trace.samplers.Samplers;
import io.opencensus.trace.Status;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class AdService {

  private static final String SERVICE_NAME = "addservice";
  private static final String DEFAULT_ENDPOINT = "localhost:55678";
  private static final Duration RETRY_INTERVAL = Duration.create(10, 0);
  private static final Duration EXPORT_INTERVAL = Duration.create(5, 0);

  private static final Logger logger = LogManager.getLogger(AdService.class);
  private static final Tracer tracer = Tracing.getTracer();

  @SuppressWarnings("FieldCanBeLocal")
  private static int MAX_ADS_TO_SERVE = 2;

  private Server server;
  private HealthStatusManager healthMgr;

  private static final AdService service = new AdService();

  private void start() throws IOException {
    int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "9555"));
    healthMgr = new HealthStatusManager();

    server =
        ServerBuilder.forPort(port)
            .addService(new AdServiceImpl())
            .addService(healthMgr.getHealthService())
            .build()
            .start();
    logger.info("Ad Service started, listening on " + port);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  // Use stderr here since the logger may have been reset by its JVM shutdown hook.
                  System.err.println(
                      "*** shutting down gRPC ads server since JVM is shutting down");
                  AdService.this.stop();
                  System.err.println("*** server shut down");
                }));
    healthMgr.setStatus("", ServingStatus.SERVING);
  }

  private void stop() {
    if (server != null) {
      healthMgr.clearStatus("");
      server.shutdown();
    }
  }

  private static class AdServiceImpl extends hipstershop.AdServiceGrpc.AdServiceImplBase {

    /**
     * Retrieves ads based on context provided in the request {@code AdRequest}.
     *
     * @param req the request containing context.
     * @param responseObserver the stream observer which gets notified with the value of {@code
     *     AdResponse}
     */
    @Override
    public void getAds(AdRequest req, StreamObserver<AdResponse> responseObserver) {
      AdService service = AdService.getInstance();
      // Span span = tracer.getCurrentSpan();
      try (Scope scope = tracer.spanBuilder("getAds").startScopedSpan()) {
        List<Ad> allAds = new ArrayList<>();
        logger.info("received ad request (context_words=" + req.getContextKeysList() + ")");
        if (req.getContextKeysCount() > 0) {
          try (Scope childScope = tracer.spanBuilder("getAdsByCategory").startScopedSpan()) {
            for (int i = 0; i < req.getContextKeysCount(); i++) {
              Collection<Ad> ads = service.getAdsByCategory(req.getContextKeys(i));
              allAds.addAll(ads);
            }
          } catch (Exception e) {
            tracer.getCurrentSpan().setStatus(Status.INTERNAL.withDescription(e.toString()));
          }
        } else {
          try (Scope childScope = tracer.spanBuilder("getRandomAds").startScopedSpan()) {
            allAds = service.getRandomAds();
          } catch (Exception e) {
            tracer.getCurrentSpan().setStatus(Status.INTERNAL.withDescription(e.toString()));
          }
        }
        if (allAds.isEmpty()) {
          allAds = service.getRandomAds();
        }
        AdResponse reply = AdResponse.newBuilder().addAllAds(allAds).build();
        responseObserver.onNext(reply);
        responseObserver.onCompleted();
      } catch (StatusRuntimeException e) {
        logger.log(Level.WARN, "GetAds Failed with status {}", e.getStatus());
        responseObserver.onError(e);
      }
    }
  }

  private static final ImmutableListMultimap<String, Ad> adsMap = createAdsMap();

  private Collection<Ad> getAdsByCategory(String category) {
    return adsMap.get(category);
  }

  private static final Random random = new Random();

  private List<Ad> getRandomAds() {
    List<Ad> ads = new ArrayList<>(MAX_ADS_TO_SERVE);
    Collection<Ad> allAds = adsMap.values();
    for (int i = 0; i < MAX_ADS_TO_SERVE; i++) {
      ads.add(Iterables.get(allAds, random.nextInt(allAds.size())));
    }
    return ads;
  }

  private static AdService getInstance() {
    return service;
  }

  /** Await termination on the main thread since the grpc library uses daemon threads. */
  private void blockUntilShutdown() throws InterruptedException {
    if (server != null) {
      server.awaitTermination();
    }
  }

  private static ImmutableListMultimap<String, Ad> createAdsMap() {
    Ad hairdryer =
        Ad.newBuilder()
            .setRedirectUrl("/product/2ZYFJ3GM2N")
            .setText("Hairdryer for sale. 50% off.")
            .build();
    Ad tankTop =
        Ad.newBuilder()
            .setRedirectUrl("/product/66VCHSJNUP")
            .setText("Tank top for sale. 20% off.")
            .build();
    Ad candleHolder =
        Ad.newBuilder()
            .setRedirectUrl("/product/0PUK6V6EV0")
            .setText("Candle holder for sale. 30% off.")
            .build();
    Ad bambooGlassJar =
        Ad.newBuilder()
            .setRedirectUrl("/product/9SIQT8TOJO")
            .setText("Bamboo glass jar for sale. 10% off.")
            .build();
    Ad watch =
        Ad.newBuilder()
            .setRedirectUrl("/product/1YMWWN1N4O")
            .setText("Watch for sale. Buy one, get second kit for free")
            .build();
    Ad mug =
        Ad.newBuilder()
            .setRedirectUrl("/product/6E92ZMYYFZ")
            .setText("Mug for sale. Buy two, get third one for free")
            .build();
    Ad loafers =
        Ad.newBuilder()
            .setRedirectUrl("/product/L9ECAV7KIM")
            .setText("Loafers for sale. Buy one, get second one for free")
            .build();
    return ImmutableListMultimap.<String, Ad>builder()
        .putAll("clothing", tankTop)
        .putAll("accessories", watch)
        .putAll("footwear", loafers)
        .putAll("hair", hairdryer)
        .putAll("decor", candleHolder)
        .putAll("kitchen", bambooGlassJar, mug)
        .build();
  }

  /** Main launches the server from the command line. */
  public static void main(String[] args) throws IOException, InterruptedException {
    // Always sample for demo purpose. DO NOT use in production.
    configureAlwaysSample();
    // Registers all RPC views.
    RpcViews.registerAllGrpcViews();

    String endPoint = System.getenv("OTEL_AGENT_ENDPOINT");
    if (endPoint==null) {
      endPoint = DEFAULT_ENDPOINT;
    }
    registerAgentExporters(endPoint);

    try (Scope scope = tracer.spanBuilder("root").startScopedSpan()) {
      // Start the RPC server. You shouldn't see any output from gRPC before this.
      logger.info("AdService starting.");
      final AdService service = AdService.getInstance();
      service.start();
      service.blockUntilShutdown();
    } catch (InterruptedException e) {
      logger.info("Thread interrupted, exiting in 5 seconds.");
      Thread.sleep(5000); // Wait 5s so that last batch will be exported.
    }

  }

  private static void configureAlwaysSample() {
    TraceConfig traceConfig = Tracing.getTraceConfig();
    TraceParams activeTraceParams = traceConfig.getActiveTraceParams();
    traceConfig.updateActiveTraceParams(
        activeTraceParams.toBuilder().setSampler(Samplers.alwaysSample()).build());
  }

  private static void registerAgentExporters(String endPoint) {
    OcAgentTraceExporter.createAndRegister(
        OcAgentTraceExporterConfiguration.builder()
            .setEndPoint(endPoint)
            .setServiceName(SERVICE_NAME)
            .setUseInsecure(true)
            .setEnableConfig(false)
            .build());

    OcAgentMetricsExporter.createAndRegister(
        OcAgentMetricsExporterConfiguration.builder()
            .setEndPoint(endPoint)
            .setServiceName(SERVICE_NAME)
            .setUseInsecure(true)
            .setRetryInterval(RETRY_INTERVAL)
            .setExportInterval(EXPORT_INTERVAL)
            .build());
  }

}
