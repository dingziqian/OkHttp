/*
 * Copyright (C) 2013 Square, Inc.
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
package okhttp3.internal.cache;

import java.util.Date;
import javax.annotation.Nullable;
import okhttp3.CacheControl;
import okhttp3.Headers;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.Internal;
import okhttp3.internal.http.HttpDate;
import okhttp3.internal.http.HttpHeaders;
import okhttp3.internal.http.StatusLine;

import static java.net.HttpURLConnection.HTTP_BAD_METHOD;
import static java.net.HttpURLConnection.HTTP_GONE;
import static java.net.HttpURLConnection.HTTP_MOVED_PERM;
import static java.net.HttpURLConnection.HTTP_MOVED_TEMP;
import static java.net.HttpURLConnection.HTTP_MULT_CHOICE;
import static java.net.HttpURLConnection.HTTP_NOT_AUTHORITATIVE;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_NOT_IMPLEMENTED;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_REQ_TOO_LONG;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * 缓存策略类
 * 为请求和响应设置策略，这决定是否使用网络还是使用缓存，或者两者都用
 * 选择一个缓存策略可能会为请求添加条件（如为GET请求添加“If-Modified-Since”头）或 为响应添加一个警告信息（如果缓存的数据可能是陈旧的）。
 * Given a request and cached response, this figures out whether to use the network, the cache, or
 * both.
 *
 * <p>Selecting a cache strategy may add conditions to the request (like the "If-Modified-Since"
 * header for conditional GETs) or warnings to the cached response (if the cached data is
 * potentially stale).
 */
public final class CacheStrategy {
  /**
   * 如果call没有使用网络，这个值可能为空
   * The request to send on the network, or null if this call doesn't use the network.
   */
  public final @Nullable Request networkRequest;

  /**
   * 如果为空，表明call没有使用缓存
   * The cached response to return or validate; or null if this call doesn't use a cache.
   */
  public final @Nullable Response cacheResponse;

  CacheStrategy(Request networkRequest, Response cacheResponse) {
    this.networkRequest = networkRequest;
    this.cacheResponse = cacheResponse;
  }

  /** Returns true if {@code response} can be stored to later serve another request. */
  public static boolean isCacheable(Response response, Request request) {
    // Always go to network for uncacheable response codes (RFC 7231 section 6.1),
    // This implementation doesn't support caching partial content.
    switch (response.code()) {
      case HTTP_OK:
      case HTTP_NOT_AUTHORITATIVE:
      case HTTP_NO_CONTENT:
      case HTTP_MULT_CHOICE:
      case HTTP_MOVED_PERM:
      case HTTP_NOT_FOUND:
      case HTTP_BAD_METHOD:
      case HTTP_GONE:
      case HTTP_REQ_TOO_LONG:
      case HTTP_NOT_IMPLEMENTED:
      case StatusLine.HTTP_PERM_REDIRECT:
        // These codes can be cached unless headers forbid it.
        break;

      case HTTP_MOVED_TEMP:
      case StatusLine.HTTP_TEMP_REDIRECT:
        // These codes can only be cached with the right response headers.
        // http://tools.ietf.org/html/rfc7234#section-3
        // s-maxage is not checked because OkHttp is a private cache that should ignore s-maxage.
        if (response.header("Expires") != null
            || response.cacheControl().maxAgeSeconds() != -1
            || response.cacheControl().isPublic()
            || response.cacheControl().isPrivate()) {
          break;
        }
        // Fall-through.

      default:
        // All other codes cannot be cached.
        return false;
    }

    // A 'no-store' directive on request or response prevents the response from being cached.
    return !response.cacheControl().noStore() && !request.cacheControl().noStore();
  }

  public static class Factory {
    final long nowMillis;
    final Request request;
    final Response cacheResponse;

    /**
     * 服务器的响应时间
     * The server's time when the cached response was served, if known.
     */
    private Date servedDate;
    private String servedDateString;

    /**
     * 最后修改的时间
     * The last modified date of the cached response, if known.
     */
    private Date lastModified;
    private String lastModifiedString;

    /**
     * 过期时间，如果max age同时被设置的话，max age的优先级更高
     * The expiration date of the cached response, if known. If both this field and the max age are
     * set, the max age is preferred.
     */
    private Date expires;

    /**
     * http 第一次初始化的时候设置的时间戳，这个是在okhttp的扩展header里面
     * Extension header set by OkHttp specifying the timestamp when the cached HTTP request was
     * first initiated.
     */
    private long sentRequestMillis;

    /**
     * 第一收到响应的时间戳
     * Extension header set by OkHttp specifying the timestamp when the cached HTTP response was
     * first received.
     */
    private long receivedResponseMillis;

    /**
     * Etag 服务响应请求时，告诉客户端当前资源在服务器的唯一标识
     * Etag of the cached response.
     */
    private String etag;

    /** Age of the cached response. */
    private int ageSeconds = -1;

    public Factory(long nowMillis, Request request, Response cacheResponse) {
      this.nowMillis = nowMillis;
      this.request = request;
      this.cacheResponse = cacheResponse;

      if (cacheResponse != null) {
        this.sentRequestMillis = cacheResponse.sentRequestAtMillis();
        this.receivedResponseMillis = cacheResponse.receivedResponseAtMillis();
        Headers headers = cacheResponse.headers();
        // 获取cacheReposne中的header中值
        for (int i = 0, size = headers.size(); i < size; i++) {
          String fieldName = headers.name(i);
          String value = headers.value(i);
          if ("Date".equalsIgnoreCase(fieldName)) {
            servedDate = HttpDate.parse(value);
            servedDateString = value;
          } else if ("Expires".equalsIgnoreCase(fieldName)) {
            expires = HttpDate.parse(value);
          } else if ("Last-Modified".equalsIgnoreCase(fieldName)) {
            lastModified = HttpDate.parse(value);
            lastModifiedString = value;
          } else if ("ETag".equalsIgnoreCase(fieldName)) {
            etag = value;
          } else if ("Age".equalsIgnoreCase(fieldName)) {
            ageSeconds = HttpHeaders.parseSeconds(value, -1);
          }
        }
      }
    }

    /**
     * Returns a strategy to satisfy {@code request} using the a cached response {@code response}.
     */
    public CacheStrategy get() {
      // 获取当前的缓存策略
      CacheStrategy candidate = getCandidate();
      // 如果网络请求不为null并且请求里面的cacheControl是只用缓存
      if (candidate.networkRequest != null && request.cacheControl().onlyIfCached()) {
        // We're forbidden from using the network and the cache is insufficient.
        return new CacheStrategy(null, null);
      }

      return candidate;
    }

    /** Returns a strategy to use assuming the request can use the network. */
    private CacheStrategy getCandidate() {
      // No cached response.
      // 如果没有缓存响应，返回一个没有响应的策略 第二个参数为空
      if (cacheResponse == null) {
        return new CacheStrategy(request, null);
      }

      // Drop the cached response if it's missing a required handshake.
      // 如果是https，丢失了握手，返回一个没有响应的策略 第二个参数为空
      if (request.isHttps() && cacheResponse.handshake() == null) {
        return new CacheStrategy(request, null);
      }

      // If this response shouldn't have been stored, it should never be used
      // as a response source. This check should be redundant as long as the
      // persistence store is well-behaved and the rules are constant.
      // 如果响应不能被缓存,那么它就不能被用作响应源，
      // 只要持久性存储行为良好且规则不变，此检查应该是多余的。
      // 不能被缓存，返回一个没有响应的策略 第二个参数为空
      if (!isCacheable(cacheResponse, request)) {
        return new CacheStrategy(request, null);
      }

      // 如果请求里面设置了不缓存，则不缓存
      CacheControl requestCaching = request.cacheControl();
      if (requestCaching.noCache() || hasConditions(request)) {
        return new CacheStrategy(request, null);
      }

      // 响应内容一直不变，则不缓存
      CacheControl responseCaching = cacheResponse.cacheControl();
      if (responseCaching.immutable()) {
        return new CacheStrategy(null, cacheResponse);
      }

      // 获取响应的年龄
      long ageMillis = cacheResponseAge();
      // 获取上次响应刷新的时间
      long freshMillis = computeFreshnessLifetime();

      if (requestCaching.maxAgeSeconds() != -1) {
        freshMillis = Math.min(freshMillis, SECONDS.toMillis(requestCaching.maxAgeSeconds()));
      }

      // 如果请求里面有最小刷新时间的限制
      long minFreshMillis = 0;
      if (requestCaching.minFreshSeconds() != -1) {
        // 用请求中的最小更新时间来更新最小时间限制
        minFreshMillis = SECONDS.toMillis(requestCaching.minFreshSeconds());
      }

      //最大验证时间
      long maxStaleMillis = 0;
      // 如果响应(服务器)那边不是必须验证并且存在最大验证秒数
      if (!responseCaching.mustRevalidate() && requestCaching.maxStaleSeconds() != -1) {
        //更新最大验证时间
        maxStaleMillis = SECONDS.toMillis(requestCaching.maxStaleSeconds());
      }

      // 响应支持缓存
      // 持续时间+最短刷新时间<上次刷新时间+最大验证时间 则可以缓存
      // 现在时间(now)-已经过去的时间（sent）+可以存活的时间<最大存活时间(max-age)
      if (!responseCaching.noCache() && ageMillis + minFreshMillis < freshMillis + maxStaleMillis) {
        Response.Builder builder = cacheResponse.newBuilder();
        if (ageMillis + minFreshMillis >= freshMillis) {
          builder.addHeader("Warning", "110 HttpURLConnection \"Response is stale\"");
        }
        long oneDayMillis = 24 * 60 * 60 * 1000L;
        if (ageMillis > oneDayMillis && isFreshnessLifetimeHeuristic()) {
          builder.addHeader("Warning", "113 HttpURLConnection \"Heuristic expiration\"");
        }
        // 缓存响应
        return new CacheStrategy(null, builder.build());
      }

      // Find a condition to add to the request. If the condition is satisfied, the response body
      // will not be transmitted.
      // 如果想缓存request，必须要满足一定的条件
      String conditionName;
      String conditionValue;
      if (etag != null) {
        conditionName = "If-None-Match";
        conditionValue = etag;
      } else if (lastModified != null) {
        conditionName = "If-Modified-Since";
        conditionValue = lastModifiedString;
      } else if (servedDate != null) {
        conditionName = "If-Modified-Since";
        conditionValue = servedDateString;
      } else {
        // 没有条件则返回一个定期的request
        return new CacheStrategy(request, null); // No condition! Make a regular request.
      }

      // //返回有条件的缓存request策略
      Headers.Builder conditionalRequestHeaders = request.headers().newBuilder();
      Internal.instance.addLenient(conditionalRequestHeaders, conditionName, conditionValue);

      Request conditionalRequest = request.newBuilder()
          .headers(conditionalRequestHeaders.build())
          .build();
      return new CacheStrategy(conditionalRequest, cacheResponse);
    }

    /**
     * 返回响应刷新的时间，从请求服务器开始计时
     * Returns the number of milliseconds that the response was fresh for, starting from the served
     * date.
     */
    private long computeFreshnessLifetime() {
      CacheControl responseCaching = cacheResponse.cacheControl();
      if (responseCaching.maxAgeSeconds() != -1) {
        return SECONDS.toMillis(responseCaching.maxAgeSeconds());
      } else if (expires != null) {
        long servedMillis = servedDate != null
            ? servedDate.getTime()
            : receivedResponseMillis;
        long delta = expires.getTime() - servedMillis;
        return delta > 0 ? delta : 0;
      } else if (lastModified != null
          && cacheResponse.request().url().query() == null) {
        // As recommended by the HTTP RFC and implemented in Firefox, the
        // max age of a document should be defaulted to 10% of the
        // document's age at the time it was served. Default expiration
        // dates aren't used for URIs containing a query.
        long servedMillis = servedDate != null
            ? servedDate.getTime()
            : sentRequestMillis;
        long delta = servedMillis - lastModified.getTime();
        return delta > 0 ? (delta / 10) : 0;
      }
      return 0;
    }

    /**
     * 返回当前响应的age，毫秒级别
     * Returns the current age of the response, in milliseconds. The calculation is specified by RFC
     * 7234, 4.2.3 Calculating Age.
     */
    private long cacheResponseAge() {
      long apparentReceivedAge = servedDate != null
          ? Math.max(0, receivedResponseMillis - servedDate.getTime())
          : 0;
      long receivedAge = ageSeconds != -1
          ? Math.max(apparentReceivedAge, SECONDS.toMillis(ageSeconds))
          : apparentReceivedAge;
      long responseDuration = receivedResponseMillis - sentRequestMillis;
      long residentDuration = nowMillis - receivedResponseMillis;
      return receivedAge + responseDuration + residentDuration;
    }

    /**
     * Returns true if computeFreshnessLifetime used a heuristic. If we used a heuristic to serve a
     * cached response older than 24 hours, we are required to attach a warning.
     */
    private boolean isFreshnessLifetimeHeuristic() {
      return cacheResponse.cacheControl().maxAgeSeconds() == -1 && expires == null;
    }

    /**
     * 当一个请求使用自己的条件排队时，内置的响应缓存将不会被使用。
     * Returns true if the request contains conditions that save the server from sending a response
     * that the client has locally. When a request is enqueued with its own conditions, the built-in
     * response cache won't be used.
     */
    private static boolean hasConditions(Request request) {
      return request.header("If-Modified-Since") != null || request.header("If-None-Match") != null;
    }
  }
}
