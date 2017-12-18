/*
 * Copyright (C) 2016 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal.http;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.HttpRetryException;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.security.cert.CertificateException;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocketFactory;
import okhttp3.Address;
import okhttp3.Call;
import okhttp3.CertificatePinner;
import okhttp3.Connection;
import okhttp3.EventListener;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.Route;
import okhttp3.internal.connection.RouteException;
import okhttp3.internal.connection.StreamAllocation;
import okhttp3.internal.http2.ConnectionShutdownException;

import static java.net.HttpURLConnection.HTTP_CLIENT_TIMEOUT;
import static java.net.HttpURLConnection.HTTP_MOVED_PERM;
import static java.net.HttpURLConnection.HTTP_MOVED_TEMP;
import static java.net.HttpURLConnection.HTTP_MULT_CHOICE;
import static java.net.HttpURLConnection.HTTP_PROXY_AUTH;
import static java.net.HttpURLConnection.HTTP_SEE_OTHER;
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;
import static okhttp3.internal.Util.closeQuietly;
import static okhttp3.internal.http.StatusLine.HTTP_PERM_REDIRECT;
import static okhttp3.internal.http.StatusLine.HTTP_TEMP_REDIRECT;

/**
 * 负责失败重试以及重定向
 * This interceptor recovers from failures and follows redirects as necessary. It may throw an
 * {@link IOException} if the call was canceled.
 */
public final class RetryAndFollowUpInterceptor implements Interceptor {
  /**
   * 允许重定向的重试次数
   * How many redirects and auth challenges should we attempt? Chrome follows 21 redirects; Firefox,
   * curl, and wget follow 20; Safari follows 16; and HTTP/1.0 recommends 5.
   */
  private static final int MAX_FOLLOW_UPS = 20;

  private final OkHttpClient client;
  private final boolean forWebSocket;
  private StreamAllocation streamAllocation;
  private Object callStackTrace;
  private volatile boolean canceled;

  public RetryAndFollowUpInterceptor(OkHttpClient client, boolean forWebSocket) {
    this.client = client;
    this.forWebSocket = forWebSocket;
  }

  /**
   * 如果当前有持有则立即关闭socket的连接，使用这个方法来停止任何线程的请求，而关闭请求的流是调用者负责的
   * 如果多次调用这个方法是安全的，已经建立的传输层连接（例如HTTP / 2流）将会被终止。如果套接字连接正在建立，则终止。
   * Immediately closes the socket connection if it's currently held. Use this to interrupt an
   * in-flight request from any thread. It's the caller's responsibility to close the request body
   * and response body streams; otherwise resources may be leaked.
   *
   * <p>This method is safe to be called concurrently, but provides limited guarantees. If a
   * transport layer connection has been established (such as a HTTP/2 stream) that is terminated.
   * Otherwise if a socket connection is being established, that is terminated.
   */
  public void cancel() {
    canceled = true;
    StreamAllocation streamAllocation = this.streamAllocation;
    if (streamAllocation != null) streamAllocation.cancel();
  }

  // 获取cancle状态
  public boolean isCanceled() {
    return canceled;
  }

  // 设置调用堆栈跟踪，在创建StreamAllocation对象的时候作为参数传入
  public void setCallStackTrace(Object callStackTrace) {
    this.callStackTrace = callStackTrace;
  }

  // 获取StreamAllocation，一个流分配的类，处理连接，数据流，Call请求的关系
  public StreamAllocation streamAllocation() {
    return streamAllocation;
  }


  @Override public Response intercept(Chain chain) throws IOException {
    Request request = chain.request();
    RealInterceptorChain realChain = (RealInterceptorChain) chain;
    Call call = realChain.call();
    EventListener eventListener = realChain.eventListener();
    // 三个参数分别对应：(1)全局的连接池，(2)连接线路Address, (3)call,(4)监听器 (5)堆栈对象
    streamAllocation = new StreamAllocation(client.connectionPool(), createAddress(request.url()),
        call, eventListener, callStackTrace);

    int followUpCount = 0;
    Response priorResponse = null;
    // 开启循环

    while (true) {
      if (canceled) {// 是否已经cancel
        streamAllocation.release();
        throw new IOException("Canceled");
      }

      // 调用下一个拦截器，等待下一个拦截器返回结果
      Response response;
      boolean releaseConnection = true;
      // 如果发生异常，走到catch里面，判断是否继续请求，不继续请求则退出
      try {
        // 会将初始化好的连接对象传递给下一个拦截器，也是贯穿整个请求的连击对象，
        // 在拦截器执行过程中，RealInterceptorChain的几个属性字段会一步一步赋值
        response = realChain.proceed(request, streamAllocation, null, null);
        releaseConnection = false;
      } catch (RouteException e) {

        // The attempt to connect via a route failed. The request will not have been sent.
        // 尝试连接路由失败，请求可能已经发送。
        if (!recover(e.getLastConnectException(), false, request)) {
          throw e.getLastConnectException();
        }
        releaseConnection = false;
        continue;
      } catch (IOException e) {
        // An attempt to communicate with a server failed. The request may have been sent.
        // 尝试与服务器通信失败。 请求可能已经发送。
        boolean requestSendStarted = !(e instanceof ConnectionShutdownException);
        if (!recover(e, requestSendStarted, request)) throw e;
        releaseConnection = false;
        continue;
      } finally {
        // We're throwing an unchecked exception. Release any resources.
        if (releaseConnection) {//释放资源
          streamAllocation.streamFailed(null);
          streamAllocation.release();
        }
      }

      // Attach the prior response if it exists. Such responses never have a body.
      // 如果priorResponse不为空，则说明前面已经获取到了响应，这里会结合当前获取的Response和先前的Response
      if (priorResponse != null) { // 前一个response
        response = response.newBuilder()
            .priorResponse(priorResponse.newBuilder()
                    .body(null)
                    .build())
            .build();
      }

      // 调用followUpRequest查看响应是否需要重定向，如果不需要重定向则返回当前请求
      Request followUp = followUpRequest(response);

      if (followUp == null) { // 无须重定向
        if (!forWebSocket) {
          streamAllocation.release();
        }
        // 如果一个response是不需要重定向的，这这里直接返回
        return response;
      }

      closeQuietly(response.body());
      // 重定向次数+1，同时判断是否达到最大限制数量(20)
      if (++followUpCount > MAX_FOLLOW_UPS) {
        streamAllocation.release();
        throw new ProtocolException("Too many follow-up requests: " + followUpCount);
      }

      if (followUp.body() instanceof UnrepeatableRequestBody) {
        streamAllocation.release();
        throw new HttpRetryException("Cannot retry streamed HTTP body", response.code());
      }
      // 检查是否有相同的链接
      if (!sameConnection(response, followUp.url())) {// host url等不同，释放，重建创建
        streamAllocation.release();
        streamAllocation = new StreamAllocation(client.connectionPool(),
            createAddress(followUp.url()), call, eventListener, callStackTrace);
      } else if (streamAllocation.codec() != null) {
        throw new IllegalStateException("Closing the body of " + response
            + " didn't close its backing stream. Bad interceptor?");
      }
      //重新设置request，并把当前的Response保存到priorResponse，继续while循环
      request = followUp;
      priorResponse = response;
    }
  }

  // 通过url的host，port, dns 以及一系列ohHttpClient的协议连接参数，建立一个Address类。而这个Address对象是提供给StreamAllocation，建立连接使用的。
  private Address createAddress(HttpUrl url) {
    SSLSocketFactory sslSocketFactory = null;
    HostnameVerifier hostnameVerifier = null;
    CertificatePinner certificatePinner = null;
    if (url.isHttps()) {
      sslSocketFactory = client.sslSocketFactory();
      hostnameVerifier = client.hostnameVerifier();
      certificatePinner = client.certificatePinner();
    }

    return new Address(url.host(), url.port(), client.dns(), client.socketFactory(),
        sslSocketFactory, hostnameVerifier, certificatePinner, client.proxyAuthenticator(),
        client.proxy(), client.protocols(), client.connectionSpecs(), client.proxySelector());
  }

  /**
   * 记录并且尝试从一个已经失败的通信中恢复，如果{@code e}是可恢复的会返回true，如果失败是永久的则返回false
   * 对于有body的Requests，只有body被buffered或者发送请求之前发生故障 这种情况下才能被恢复
   * Report and attempt to recover from a failure to communicate with a server. Returns true if
   * {@code e} is recoverable, or false if the failure is permanent. Requests with a body can only
   * be recovered if the body is buffered or if the failure occurred before the request has been
   * sent.
   */
  private boolean recover(IOException e, boolean requestSendStarted, Request userRequest) {
    streamAllocation.streamFailed(e);

    // The application layer has forbidden retries.
    // 1. 应用层配置不再连接，默认为true
    if (!client.retryOnConnectionFailure()) return false;

    // We can't send the request body again.
    // 2. 请求Request出错不能继续使用
    if (requestSendStarted && userRequest.body() instanceof UnrepeatableRequestBody) return false;

    // This exception is fatal.
    // 3. 这个exception是致命的
    if (!isRecoverable(e, requestSendStarted)) return false;

    // No more routes to attempt.
    // 4. 没用更多线路可供选择
    if (!streamAllocation.hasMoreRoutes()) return false;

    // For failure recovery, use the same route selector with a new connection.
    return true;
  }

  /**
   * 是否可恢复
   *  1、如果是协议错误（ProtocolException）则不可恢复
      2、如果是中断异常（InterruptedIOException）则不可恢复
      3、如果是SSL握手错误（SSLHandshakeException && CertificateException）则不可恢复
      4、certificate pinning错误（SSLPeerUnverifiedException）则不可恢复
   * @param e
   * @param requestSendStarted
   * @return
   */
  private boolean isRecoverable(IOException e, boolean requestSendStarted) {
    // If there was a protocol problem, don't recover.
    // 协议问题
    if (e instanceof ProtocolException) {
      return false;
    }

    // If there was an interruption don't recover, but if there was a timeout connecting to a route
    // we should try the next route (if there is one).
    // 中断不能恢复，但是如果有连接到路由的超时，我们应该尝试下一个路由（如果有的话）。
    if (e instanceof InterruptedIOException) {
      return e instanceof SocketTimeoutException && !requestSendStarted;
    }

    // Look for known client-side or negotiation errors that are unlikely to be fixed by trying
    // again with a different route.
    // 寻找已知的客户端错误，这些错误是不太可能通过不同的路由来修复
    if (e instanceof SSLHandshakeException) {
      // If the problem was a CertificateException from the X509TrustManager,
      // do not retry.
      // 如果是一个证书验证问题，我们将不在恢复
      if (e.getCause() instanceof CertificateException) {
        return false;
      }
    }
    if (e instanceof SSLPeerUnverifiedException) {
      // e.g. a certificate pinning error.
      return false;
    }

    // An example of one we might want to retry with a different route is a problem connecting to a
    // proxy and would manifest as a standard IOException. Unless it is one we know we should not
    // retry, we return true and try a new route.
    // 对于连接到代理的我问题我们想要通过不同的路由来重试，这时候我们将其显示为标准的IOException
    // 除非我们知道我们不应该再试一次，否则我们会返回真实并尝试新的路线。
    return true;
  }

  /**
   * 计算request到response再到收到code，将会添加authentication headers，遵循重定向或处理客户端请求超时
   * 如果后续措施不必要或不适用，则返回null。
   * Figures out the HTTP request to make in response to receiving {@code userResponse}. This will
   * either add authentication headers, follow redirects or handle a client request timeout. If a
   * follow-up is either unnecessary or not applicable, this returns null.
   */
  private Request followUpRequest(Response userResponse) throws IOException {
    if (userResponse == null) throw new IllegalStateException();
    Connection connection = streamAllocation.connection();
    Route route = connection != null
        ? connection.route()
        : null;
    int responseCode = userResponse.code();

    final String method = userResponse.request().method();
    switch (responseCode) {
      case HTTP_PROXY_AUTH: // 407
        Proxy selectedProxy = route != null
            ? route.proxy()
            : client.proxy();
        if (selectedProxy.type() != Proxy.Type.HTTP) {
          throw new ProtocolException("Received HTTP_PROXY_AUTH (407) code while not using proxy");
        }
        return client.proxyAuthenticator().authenticate(route, userResponse);

      case HTTP_UNAUTHORIZED: // 401
        return client.authenticator().authenticate(route, userResponse);

      case HTTP_PERM_REDIRECT:
      case HTTP_TEMP_REDIRECT:
        // "If the 307 or 308 status code is received in response to a request other than GET
        // or HEAD, the user agent MUST NOT automatically redirect the request"
        if (!method.equals("GET") && !method.equals("HEAD")) {
          return null;
        }
        // fall-through
      case HTTP_MULT_CHOICE:
      case HTTP_MOVED_PERM:
      case HTTP_MOVED_TEMP:
      case HTTP_SEE_OTHER:
        // Does the client allow redirects?
        // 不允许重定向，直接返回空
        if (!client.followRedirects()) return null;

        String location = userResponse.header("Location");
        if (location == null) return null;
        HttpUrl url = userResponse.request().url().resolve(location);

        // Don't follow redirects to unsupported protocols.
        // 不会重定向到不支持的协议
        if (url == null) return null;

        // If configured, don't follow redirects between SSL and non-SSL.
        // 如果配置了，就不支持ssl和非ssl之间的重定向
        boolean sameScheme = url.scheme().equals(userResponse.request().url().scheme());
        if (!sameScheme && !client.followSslRedirects()) return null;

        // Most redirects don't include a request body.
        // 大部分的重定向是不包含body的
        Request.Builder requestBuilder = userResponse.request().newBuilder();
        if (HttpMethod.permitsRequestBody(method)) {
          final boolean maintainBody = HttpMethod.redirectsWithBody(method);
          if (HttpMethod.redirectsToGet(method)) {
            requestBuilder.method("GET", null);
          } else {
            RequestBody requestBody = maintainBody ? userResponse.request().body() : null;
            requestBuilder.method(method, requestBody);
          }
          if (!maintainBody) {
            requestBuilder.removeHeader("Transfer-Encoding");
            requestBuilder.removeHeader("Content-Length");
            requestBuilder.removeHeader("Content-Type");
          }
        }

        // When redirecting across hosts, drop all authentication headers. This
        // is potentially annoying to the application layer since they have no
        // way to retain them.
        // 重定向到主机时，需要删除authentication
        if (!sameConnection(userResponse, url)) {
          requestBuilder.removeHeader("Authorization");
        }

        return requestBuilder.url(url).build();

      case HTTP_CLIENT_TIMEOUT: // 408
        // 408's are rare in practice, but some servers like HAProxy use this response code. The
        // spec says that we may repeat the request without modifications. Modern browsers also
        // repeat the request (even non-idempotent ones.)
        // 408在生产环境中很少见，但是有些服务器还是会使用这个响应码，
        // 规范中说明，我们可以不加修改地重复请求。 现代浏览器也重复这个请求（甚至是非幂等的）。
        if (!client.retryOnConnectionFailure()) {
          // The application layer has directed us not to retry the request.
          // 应用层已经重定向，我们就不需要再次重试这个请求了
          return null;
        }

        if (userResponse.request().body() instanceof UnrepeatableRequestBody) {
          return null;
        }

        if (userResponse.priorResponse() != null
            && userResponse.priorResponse().code() == HTTP_CLIENT_TIMEOUT) {
          // We attempted to retry and got another timeout. Give up.
          return null;
        }

        return userResponse.request();

      default:
        return null;
    }
  }

  /**
   * 如果一个HTTP请求{followUp}可以重用(response)这个引擎使用的连接 返回true
   * Returns true if an HTTP request for {@code followUp} can reuse the connection used by this
   * engine.
   */
  private boolean sameConnection(Response response, HttpUrl followUp) {
    HttpUrl url = response.request().url();
    return url.host().equals(followUp.host())
        && url.port() == followUp.port()
        && url.scheme().equals(followUp.scheme());
  }
}
