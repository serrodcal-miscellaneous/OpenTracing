import brave.Tracing;
import brave.opentracing.BraveTracer;
import com.google.common.collect.ImmutableMap;
import io.dropwizard.Configuration;
import io.dropwizard.setup.Environment;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMapInjectAdapter;
import io.opentracing.tag.Tags;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import zipkin2.reporter.AsyncReporter;
import zipkin2.reporter.Reporter;
import zipkin2.reporter.Sender;
import zipkin2.reporter.okhttp3.OkHttpSender;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class Hello {

  private final Tracer tracer;
  private final OkHttpClient client;

  public Hello(Tracer tracer){
    this.tracer = tracer;
    this.client = new OkHttpClient();
  }

  @Path("/hello")
  @Produces(MediaType.TEXT_PLAIN)
  public class HelloResource{

    @GET
    public String format(@QueryParam("helloStr") String helloStr, @QueryParam("greeting") String greeting, @Context HttpHeaders httpHeaders){
      Span span = tracer.buildSpan("helloSpan").withTag("prueba","prueba2").start();

      System.out.println(helloStr);
      span.log(ImmutableMap.of("event","println","value",helloStr));

      Map<String,String> map = new HashMap<>();
      tracer.inject(span.context(), Format.Builtin.HTTP_HEADERS, new TextMapInjectAdapter(map));
      return "published";
    }

  }

  @Override
  public void run(Configuration configuration, Environment environment) throws Exception{
    environment.jersey().register(new HelloResource());
  }

  private static class RequestBuilderCarrier implements io.opentracing.propagation.TextMap {

    private final Request.Builder builder;

    RequestBuilderCarrier(Request.Builder builder) {
      this.builder = builder;
    }

      @Override
      public Iterator<Map.Entry<String, String>> iterator() {
        throw new UnsupportedOperationException("carrier is write-only");
      }

      @Override
      public void put(String key, String value) {
        builder.addHeader(key, value);
      }

  }

  private String getHttp(int port, String path, String param, String value) {
    Span span = tracer.buildSpan("get-http").start();
    try {
      HttpUrl url = new HttpUrl().Builder().scheme("http").host("localhost").port(port).addPathSegment(path)
              .addQueryParameter(param, value).build();
      Request.Builder requestBuilder = new Request.Builder().url(url);

      Tags.SPAN_KIND.set(span, Tags.SPAN_KIND_CLIENT);
      Tags.HTTP_METHOD.set(span, "GET");
      Tags.HTTP_URL.set(span, url.toString());
      tracer.inject(span.context(), Format.Builtin.HTTP_HEADERS, new RequestBuilderCarrier(requestBuilder));

      Request request = requestBuilder.build();
      Response response = client.newCall(request).execute();
      if (response.code() != 200) {
        throw new RuntimeException("Bad HTTP result: " + response);
      }
      return response.body().string();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void sayHello(String helloTo, String greeting){
    String helloStr = formatString(helloTo);
    printHello(helloStr);
  }

  private String formatString(String helloTo) {
    String helloStr = getHttp(8081, "format", "helloTo", helloTo);
  }

  private void printHello(String helloStr) {
    getHttp(8082, "publish", "helloStr", helloStr);
  }

  public static void main(String[] args) {
    //Zipkin configuration, using zipkin-sender-okhttp3 and brave-opentracing dependencies
    Sender sender = OkHttpSender.create("http://zipkin:9411/api/v2/spans");
    Reporter spanReporter = AsyncReporter.create(sender);
    Tracing braveTracing = Tracing.newBuilder().localServiceName("hello").spanReporter(spanReporter).build();
    Tracer tracer = BraveTracer.create(braveTracing);

    //Init publisher server.
    new Hello(tracer).run(args);

  }

}
