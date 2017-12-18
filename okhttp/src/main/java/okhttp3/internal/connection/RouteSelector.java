/*
 * Copyright (C) 2012 Square, Inc.
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
package okhttp3.internal.connection;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import okhttp3.Address;
import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.HttpUrl;
import okhttp3.Route;
import okhttp3.internal.Util;

/**
 * 路由选择器
 *
 * 选择连接到服务器的路由，每个连接应该是代理服务器/IP地址/TLS模式 三者中的一种。连接也可以被回收
 * Selects routes to connect to an origin server. Each connection requires a choice of proxy server,
 * IP address, and TLS mode. Connections may also be recycled.
 */
public final class RouteSelector {
  private final Address address;
  private final RouteDatabase routeDatabase;
  private final Call call;
  private final EventListener eventListener;

  /* State for negotiating the next proxy to use. */
  private List<Proxy> proxies = Collections.emptyList();
  private int nextProxyIndex;

  /* State for negotiating the next socket address to use. */
  private List<InetSocketAddress> inetSocketAddresses = Collections.emptyList();

  /* State for negotiating failed routes */
  // 里面存放的是之前失败链接的路由，目的是在前所有不符合的情况，把之前失败的路由再试一次。
  private final List<Route> postponedRoutes = new ArrayList<>();

  public RouteSelector(Address address, RouteDatabase routeDatabase, Call call,
      EventListener eventListener) {
    this.address = address;
    this.routeDatabase = routeDatabase;
    this.call = call;
    this.eventListener = eventListener;

    resetNextProxy(address.url(), address.proxy());
  }

  /**
   * 如果有另外的一个代理集合可用，则返回true。每一个地址至少有一个代理
   * Returns true if there's another set of routes to attempt. Every address has at least one route.
   */
  public boolean hasNext() {
    return hasNextProxy() || !postponedRoutes.isEmpty();
  }

  /**
   * 取出下一个路由
   * @return
   * @throws IOException
   */
  public Selection next() throws IOException {
    if (!hasNext()) {
      throw new NoSuchElementException();
    }

    // Compute the next set of routes to attempt.
    List<Route> routes = new ArrayList<>();
    while (hasNextProxy()) {
      // 延迟的路由总是最后一个被尝试，例如，如果我们有两个代理,并且所有路由对于proxy1来说都是被延迟的
      // 我们将会移动到proxy2，只有我们耗尽了所有的好路线，我们才会尝试延迟的路线。
      // Postponed routes are always tried last. For example, if we have 2 proxies and all the
      // routes for proxy1 should be postponed, we'll move to proxy2. Only after we've exhausted
      // all the good routes will we attempt the postponed routes.
      Proxy proxy = nextProxy();
      for (int i = 0, size = inetSocketAddresses.size(); i < size; i++) {
        Route route = new Route(address, proxy, inetSocketAddresses.get(i));
        if (routeDatabase.shouldPostpone(route)) {
          postponedRoutes.add(route);
        } else {
          routes.add(route);
        }
      }

      if (!routes.isEmpty()) {
        break;
      }
    }

    if (routes.isEmpty()) {
      // 我们已经用尽所有的代理，所以回落到推迟的路线。
      // We've exhausted all Proxies so fallback to the postponed routes.
      routes.addAll(postponedRoutes);
      postponedRoutes.clear();
    }

    return new Selection(routes);
  }

  /**
   * 当这个路由选择器返回的连接遇到连接失败时，客户端应该调用这个方法。这个方法会将失败的router放入到黑名单里面
   * Clients should invoke this method when they encounter a connectivity failure on a connection
   * returned by this route selector.
   */
  public void connectFailed(Route failedRoute, IOException failure) {
    if (failedRoute.proxy().type() != Proxy.Type.DIRECT && address.proxySelector() != null) {
      // Tell the proxy selector when we fail to connect on a fresh connection.
      address.proxySelector().connectFailed(
          address.url().uri(), failedRoute.proxy().address(), failure);
    }

    routeDatabase.failed(failedRoute);
  }

  /**
   *
   * Prepares the proxy servers to try.
   */
  private void resetNextProxy(HttpUrl url, Proxy proxy) {
    if (proxy != null) {
      // If the user specifies a proxy, try that and only that.
      proxies = Collections.singletonList(proxy);
    } else {
      // Try each of the ProxySelector choices until one connection succeeds.
      List<Proxy> proxiesOrNull = address.proxySelector().select(url.uri());
      proxies = proxiesOrNull != null && !proxiesOrNull.isEmpty()
          ? Util.immutableList(proxiesOrNull)
          : Util.immutableList(Proxy.NO_PROXY);
    }
    nextProxyIndex = 0;
  }

  /**
   * 是否还有代理
   * Returns true if there's another proxy to try.
   */
  private boolean hasNextProxy() {
    return nextProxyIndex < proxies.size();
  }

  /**
   * 取出下一个代理，不会返回为空，但是可能会返回PROXY.NO_PROXY
   * Returns the next proxy to try. May be PROXY.NO_PROXY but never null.
   */
  private Proxy nextProxy() throws IOException {
    if (!hasNextProxy()) {
      throw new SocketException("No route to " + address.url().host()
          + "; exhausted proxy configurations: " + proxies);
    }
    Proxy result = proxies.get(nextProxyIndex++);
    resetNextInetSocketAddress(result);
    return result;
  }

  /**
   *
   * Prepares the socket addresses to attempt for the current proxy or host.
   */
  private void resetNextInetSocketAddress(Proxy proxy) throws IOException {
    // Clear the addresses. Necessary if getAllByName() below throws!
    inetSocketAddresses = new ArrayList<>();

    String socketHost;
    int socketPort;
    if (proxy.type() == Proxy.Type.DIRECT || proxy.type() == Proxy.Type.SOCKS) {
      socketHost = address.url().host();
      socketPort = address.url().port();
    } else {
      SocketAddress proxyAddress = proxy.address();
      if (!(proxyAddress instanceof InetSocketAddress)) {
        throw new IllegalArgumentException(
            "Proxy.address() is not an " + "InetSocketAddress: " + proxyAddress.getClass());
      }
      InetSocketAddress proxySocketAddress = (InetSocketAddress) proxyAddress;
      socketHost = getHostString(proxySocketAddress);
      socketPort = proxySocketAddress.getPort();
    }

    if (socketPort < 1 || socketPort > 65535) {
      throw new SocketException("No route to " + socketHost + ":" + socketPort
          + "; port is out of range");
    }

    if (proxy.type() == Proxy.Type.SOCKS) {
      inetSocketAddresses.add(InetSocketAddress.createUnresolved(socketHost, socketPort));
    } else {
      eventListener.dnsStart(call, socketHost);

      // Try each address for best behavior in mixed IPv4/IPv6 environments.
      List<InetAddress> addresses = address.dns().lookup(socketHost);
      if (addresses.isEmpty()) {
        throw new UnknownHostException(address.dns() + " returned no addresses for " + socketHost);
      }

      eventListener.dnsEnd(call, socketHost, addresses);

      for (int i = 0, size = addresses.size(); i < size; i++) {
        InetAddress inetAddress = addresses.get(i);
        inetSocketAddresses.add(new InetSocketAddress(inetAddress, socketPort));
      }
    }
  }

  /**
   * 从{@link InetSocketAddress}中获取host，这将返回一个包含实际主机名或ip的字符串。
   * Obtain a "host" from an {@link InetSocketAddress}. This returns a string containing either an
   * actual host name or a numeric IP address.
   */
  // Visible for testing
  static String getHostString(InetSocketAddress socketAddress) {
    InetAddress address = socketAddress.getAddress();
    if (address == null) {
      // The InetSocketAddress was specified with a string (either a numeric IP or a host name). If
      // it is a name, all IPs for that name should be tried. If it is an IP address, only that IP
      // address should be tried.
      return socketAddress.getHostName();
    }
    // The InetSocketAddress has a specific address: we should only try that address. Therefore we
    // return the address and ignore any host name that may be available.
    return address.getHostAddress();
  }

  /** A set of selected Routes. */
  public static final class Selection {
    private final List<Route> routes;
    private int nextRouteIndex = 0;

    Selection(List<Route> routes) {
      this.routes = routes;
    }

    public boolean hasNext() {
      return nextRouteIndex < routes.size();
    }

    public Route next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      return routes.get(nextRouteIndex++);
    }

    public List<Route> getAll() {
      return new ArrayList<>(routes);
    }
  }
}
