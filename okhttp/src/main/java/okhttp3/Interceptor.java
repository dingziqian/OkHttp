/*
 * Copyright (C) 2014 Square, Inc.
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
package okhttp3;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * 观察，修改以及可能短路的请求输出和响应请求的回来。通常情况下拦截器用来添加，移除或者转换请求或者回应的头部信息
 * Observes, modifies, and potentially short-circuits requests going out and the corresponding
 * responses coming back in. Typically interceptors add, remove, or transform headers on the request
 * or response.
 */
public interface Interceptor {
  // 这个方法负责拦截
  Response intercept(Chain chain) throws IOException;

  // 这个是正在的链
  interface Chain {
    Request request();
    // 负责分发，前行
    Response proceed(Request request) throws IOException;

    /**
     *
     * Returns the connection the request will be executed on. This is only available in the chains
     * of network interceptors; for application interceptors this is always null.
     */
    @Nullable Connection connection();

    Call call();

    int connectTimeoutMillis();

    Chain withConnectTimeout(int timeout, TimeUnit unit);

    int readTimeoutMillis();

    Chain withReadTimeout(int timeout, TimeUnit unit);

    int writeTimeoutMillis();

    Chain withWriteTimeout(int timeout, TimeUnit unit);
  }
}
