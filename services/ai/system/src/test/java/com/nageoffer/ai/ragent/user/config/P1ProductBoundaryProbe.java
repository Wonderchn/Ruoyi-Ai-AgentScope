/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.user.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * P1.2a 产品边界探针：在真实产品 classpath 里打印一行机器可读证据。
 *
 * <p><b>不是测试类</b>：没有 {@code @Test}，类名也不匹配 surefire 的默认包含规则，
 * 因此不会被单测选中，只由外部 PowerShell runner 用 {@code java -cp ... P1ProductBoundaryProbe} 执行。
 *
 * <p>它只读不写：不启动 Spring 上下文、不建连接、不改系统属性、不调用 {@code System.exit}
 * （探针可能被宿主进程内联执行，退出码由 {@code main} 正常返回决定，即 0）。
 * 任何取值失败都不会抛异常，只会体现在 {@code probeError} / 缺省值里。
 *
 * <p>输出字段：
 * <ul>
 *   <li>{@code dispatcherTypesAllowedThroughGate}：用探活形状路径实测"能穿过入口边界进入链路"的分派类型
 *       （{@code REQUEST}/{@code ASYNC}/{@code ERROR}），即"入口过滤器允许的分派类型集合"；</li>
 *   <li>{@code dispatcherTypesRejectedByGate}：被边界直接拒绝的分派类型（{@code FORWARD}/{@code INCLUDE}）；</li>
 *   <li>{@code dispatcherTypesReachingChainOnBusinessPath}：旧业务路径上能进入链路的分派类型
 *       （只有 {@code ASYNC}/{@code ERROR}），用来区分"分派类型放行"与"路径判定放行"；</li>
 *   <li>{@code businessPathRequestDispatchStatus}：{@code REQUEST} + 旧业务路径的实测状态码（应为 404）；</li>
 *   <li>{@code saasBoundaryConfigurationOnClasspath} / {@code entryFilterOnClasspath}：类路径可见性；</li>
 *   <li>{@code filterRegistrationBeansVisible} / {@code applicationContextVisible} / {@code filterRegistrationScan}：
 *       能看到几个 {@code FilterRegistrationBean} 名字，以及这个数字是怎么来的。进程里没有运行中的上下文时
 *       按 0 且 {@code false} 处理；Spring Boot 内嵌容器不经过 {@code ContextLoaderListener}，
 *       runner 可用 {@code -Dp1.probe.application-context-accessor=<fqcn>#<静态无参方法>} 把真实上下文交给探针；</li>
 *   <li>{@code properties}：{@code ai.integration.*} 与 {@code p04.enabled} 的解析值
 *       （先系统属性、后同名环境变量，都没有时按 {@code "false"} = 关闭）。</li>
 * </ul>
 */
public final class P1ProductBoundaryProbe {

    private static final String PREFIX = "P1_PROBE ";

    private static final String BOUNDARY_CONFIGURATION =
            "com.nageoffer.ai.ragent.framework.integration.SaasBoundaryConfiguration";

    private static final String ENTRY_FILTER =
            "com.nageoffer.ai.ragent.framework.integration.SaasEntryFilter";

    private static final String FILTER_REGISTRATION_BEAN =
            "org.springframework.boot.web.servlet.FilterRegistrationBean";

    private static final String APPLICATION_CONTEXT = "org.springframework.context.ApplicationContext";

    private static final String CONTEXT_LOADER = "org.springframework.web.context.ContextLoader";

    /**
     * 可选：{@code -Dp1.probe.application-context-accessor=com.acme.Holder#current}。
     *
     * <p>Spring Boot 用内嵌容器启动时不经过 {@code ContextLoaderListener}，{@code ContextLoader}
     * 看不到上下文；runner 若能把运行中的上下文通过一个静态无参方法交给探针，就能拿到真实注册数。
     * 不配置时退回 {@code ContextLoader}，仍然只报告"看不到"，绝不猜测。
     */
    private static final String CONTEXT_ACCESSOR_PROPERTY = "p1.probe.application-context-accessor";

    /** Servlet 规范要求 ERROR 分派携带该属性；OncePerRequestFilter 据此跳过自身。 */
    private static final String ERROR_REQUEST_URI_ATTRIBUTE = "jakarta.servlet.error.request_uri";

    /** 探活形状路径：用来测"分派类型本身是否被允许穿过边界"。 */
    private static final String HEALTH_PATH = "/actuator/health";

    /** 旧业务路径：形状判定必然拒绝，用来测"路径判定"这一层。 */
    private static final String BUSINESS_PATH = "/auth/login";

    private static final List<String> TRACKED_PROPERTIES = List.of(
            "ai.integration.enabled",
            "ai.integration.customer-api.enabled",
            "ai.integration.legacy-listeners-enabled",
            "p04.enabled");

    private P1ProductBoundaryProbe() {
    }

    public static void main(String[] args) {
        DispatcherEvidence dispatcher = DispatcherEvidence.empty();
        try {
            dispatcher = measureDispatcherEvidence();
        } catch (Throwable t) {
            dispatcher = dispatcher.withError(describe(t));
        }

        RegistrationVisibility registrations = countFilterRegistrationBeans();

        StringBuilder line = new StringBuilder(384);
        line.append("{\"probe\":\"p1-product-boundary\"")
                .append(",\"dispatcherTypesAllowedThroughGate\":").append(quoteList(dispatcher.allowedThroughGate()))
                .append(",\"dispatcherTypesRejectedByGate\":").append(quoteList(dispatcher.rejectedByGate()))
                .append(",\"dispatcherTypesReachingChainOnBusinessPath\":")
                .append(quoteList(dispatcher.reachedChainOnBusinessPath()))
                .append(",\"businessPathRequestDispatchStatus\":").append(dispatcher.businessPathRequestStatus())
                .append(",\"saasBoundaryConfigurationOnClasspath\":").append(isOnClasspath(BOUNDARY_CONFIGURATION))
                .append(",\"entryFilterOnClasspath\":").append(isOnClasspath(ENTRY_FILTER))
                .append(",\"filterRegistrationBeansVisible\":").append(registrations.count())
                .append(",\"applicationContextVisible\":").append(registrations.contextVisible())
                .append(",\"filterRegistrationScan\":").append(quote(registrations.strategy()))
                .append(",\"properties\":").append(propertiesJson())
                .append(",\"probeError\":").append(dispatcher.error() == null ? "null" : quote(dispatcher.error()))
                .append('}');

        System.out.println(PREFIX + line);
    }

    // ------------------------------------------------------------------ 分派类型实测

    /**
     * 用真实 {@link Filter} 实例实测每个 {@link DispatcherType}：交给链路算"允许"，留在边界算"拒绝"。
     *
     * <p>不需要 Spring 上下文：请求/响应用 JDK 动态代理，链路用只记布尔值的替身。
     */
    private static DispatcherEvidence measureDispatcherEvidence() throws Exception {
        Filter filter = (Filter) Class.forName(ENTRY_FILTER, true, loader())
                .getDeclaredConstructor()
                .newInstance();

        List<String> allowedThroughGate = new ArrayList<>();
        List<String> rejectedByGate = new ArrayList<>();
        List<String> reachedChainOnBusinessPath = new ArrayList<>();
        int businessPathRequestStatus = 0;

        for (DispatcherType type : DispatcherType.values()) {
            ProbeOutcome healthPath = probe(filter, type, HEALTH_PATH, "GET");
            if (healthPath.reachedChain()) {
                allowedThroughGate.add(type.name());
            } else {
                rejectedByGate.add(type.name());
            }

            ProbeOutcome businessPath = probe(filter, type, BUSINESS_PATH, "POST");
            if (businessPath.reachedChain()) {
                reachedChainOnBusinessPath.add(type.name());
            }
            if (type == DispatcherType.REQUEST) {
                businessPathRequestStatus = businessPath.status();
            }
        }
        return new DispatcherEvidence(allowedThroughGate, rejectedByGate, reachedChainOnBusinessPath,
                businessPathRequestStatus, null);
    }

    private static ProbeOutcome probe(Filter filter, DispatcherType type, String uri, String method)
            throws Exception {
        Map<String, Object> attributes = new HashMap<>();
        if (type == DispatcherType.ERROR) {
            // 复现容器行为：ERROR 分派一定带错误属性。不补它测到的是容器不会产生的"裸 ERROR"
            attributes.put(ERROR_REQUEST_URI_ATTRIBUTE, uri);
        }
        HttpServletRequest request = newRequestProxy(method, uri, type, attributes);
        RecordingResponseHandler responseHandler = new RecordingResponseHandler();
        HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(
                loader(), new Class<?>[]{HttpServletResponse.class}, responseHandler);
        RecordingChain chain = new RecordingChain();
        filter.doFilter(request, response, chain);
        return new ProbeOutcome(chain.reached(), responseHandler.status());
    }

    private static HttpServletRequest newRequestProxy(String method, String uri, DispatcherType type,
                                                      Map<String, Object> attributes) {
        InvocationHandler handler = (proxy, invoked, args) -> switch (invoked.getName()) {
            case "getMethod" -> method;
            case "getRequestURI" -> uri;
            case "getContextPath" -> "";
            case "getDispatcherType" -> type;
            case "getProtocol" -> "HTTP/1.1";
            case "getScheme" -> "http";
            case "getAttribute" -> attributes.get((String) args[0]);
            case "setAttribute" -> {
                attributes.put((String) args[0], args[1]);
                yield null;
            }
            case "removeAttribute" -> {
                attributes.remove((String) args[0]);
                yield null;
            }
            case "getHeader" -> null;
            case "isSecure" -> Boolean.FALSE;
            default -> defaultValue(invoked.getReturnType());
        };
        return (HttpServletRequest) Proxy.newProxyInstance(
                loader(), new Class<?>[]{HttpServletRequest.class}, handler);
    }

    // ------------------------------------------------------------------ 装配可见性

    /**
     * 能看到几个 {@code FilterRegistrationBean} 名字。
     *
     * <p>独立 {@code java} 进程里没有运行中的 Web 上下文，此时按"看不到任何注册"处理并显式标出
     * {@code applicationContextVisible=false}，而不是猜测；拿不到就报 0、报来源，不抛异常。
     */
    private static RegistrationVisibility countFilterRegistrationBeans() {
        Class<?> registrationType;
        try {
            registrationType = Class.forName(FILTER_REGISTRATION_BEAN, false, loader());
        } catch (Throwable t) {
            return new RegistrationVisibility(0, false, "spring-boot-web-absent");
        }
        ContextLookup lookup = currentApplicationContext();
        if (lookup.context() == null) {
            return new RegistrationVisibility(0, false, "no-running-context/" + lookup.strategy());
        }
        try {
            Class<?> contextType = Class.forName(APPLICATION_CONTEXT, false, loader());
            Object names = contextType.getMethod("getBeanNamesForType", Class.class)
                    .invoke(lookup.context(), registrationType);
            return new RegistrationVisibility(names instanceof String[] array ? array.length : 0, true,
                    lookup.strategy() + "#getBeanNamesForType");
        } catch (Throwable t) {
            return new RegistrationVisibility(0, true, lookup.strategy() + "#unavailable");
        }
    }

    /**
     * 依次尝试：runner 显式交出的访问器 → {@code ContextLoader} 的当前 Web 上下文。
     *
     * <p>两条都拿不到就返回 null（调用方报"看不到"），不抛异常、不建上下文。
     */
    private static ContextLookup currentApplicationContext() {
        Object configured = applicationContextFromAccessor();
        if (configured != null) {
            return new ContextLookup(configured, CONTEXT_ACCESSOR_PROPERTY);
        }
        try {
            Class<?> contextLoader = Class.forName(CONTEXT_LOADER, false, loader());
            Object context = contextLoader.getMethod("getCurrentWebApplicationContext").invoke(null);
            return new ContextLookup(context, "ContextLoader#getCurrentWebApplicationContext");
        } catch (Throwable t) {
            return new ContextLookup(null, "ContextLoader#unavailable");
        }
    }

    /** 解析 {@code -Dp1.probe.application-context-accessor=<fqcn>#<静态无参方法>}；任何解析失败都当没配置。 */
    private static Object applicationContextFromAccessor() {
        String accessor = readProperty(CONTEXT_ACCESSOR_PROPERTY);
        if (accessor == null || accessor.isBlank()) {
            return null;
        }
        String trimmed = accessor.trim();
        int separator = trimmed.indexOf('#');
        if (separator <= 0 || separator == trimmed.length() - 1) {
            return null;
        }
        try {
            Class<?> holder = Class.forName(trimmed.substring(0, separator), false, loader());
            return holder.getMethod(trimmed.substring(separator + 1)).invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isOnClasspath(String className) {
        try {
            Class.forName(className, false, loader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static ClassLoader loader() {
        return P1ProductBoundaryProbe.class.getClassLoader();
    }

    // ------------------------------------------------------------------ 属性解析

    private static String propertiesJson() {
        StringBuilder json = new StringBuilder(192).append('{');
        for (int i = 0; i < TRACKED_PROPERTIES.size(); i++) {
            String property = TRACKED_PROPERTIES.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append(quote(property)).append(':').append(quote(resolveProperty(property)));
        }
        return json.append('}').toString();
    }

    /**
     * 先 {@link System#getProperty}、后同名环境变量；都没读到返回 {@code "false"}。
     *
     * <p>缺省必须是关闭：探针把"读不到"当成 false，绝不能反过来当成"已批准"。
     */
    private static String resolveProperty(String property) {
        String fromProperty = readProperty(property);
        if (fromProperty != null && !fromProperty.isBlank()) {
            return fromProperty.trim();
        }
        String fromEnv = readEnv(environmentName(property));
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        return "false";
    }

    /** 属性名 → 环境变量名：全大写，{@code .} 与 {@code -} 换成 {@code _}。 */
    private static String environmentName(String property) {
        return property.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    private static String readProperty(String property) {
        try {
            return System.getProperty(property);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readEnv(String name) {
        try {
            return System.getenv(name);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ JSON 拼装

    private static String quoteList(List<String> values) {
        StringBuilder json = new StringBuilder(32).append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(quote(values.get(i)));
        }
        return json.append(']').toString();
    }

    private static String quote(String value) {
        return "\"" + escape(value) + "\"";
    }

    /** 单行输出：把可能破坏 JSON/单行性的字符转义或替换掉。 */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(c < 0x20 || c == 0x7f ? ' ' : c);
            }
        }
        return escaped.toString();
    }

    private static String describe(Throwable t) {
        return t.getClass().getName() + ": " + t.getMessage();
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return Boolean.FALSE;
        }
        if (returnType == void.class) {
            return null;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == double.class) {
            return 0d;
        }
        if (returnType == float.class) {
            return 0f;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        return (char) 0;
    }

    /** 一次探测的观测结果：请求有没有被交给后续链路，以及边界给出的状态码。 */
    private record ProbeOutcome(boolean reachedChain, int status) {
    }

    /** 上下文查找结果：上下文对象（可能为 null）与它的来源说明。 */
    private record ContextLookup(Object context, String strategy) {
    }

    /** 分派类型证据。 */
    private record DispatcherEvidence(List<String> allowedThroughGate, List<String> rejectedByGate,
                                      List<String> reachedChainOnBusinessPath, int businessPathRequestStatus,
                                      String error) {

        private static DispatcherEvidence empty() {
            return new DispatcherEvidence(List.of(), List.of(), List.of(), 0, null);
        }

        private DispatcherEvidence withError(String failure) {
            return new DispatcherEvidence(allowedThroughGate, rejectedByGate, reachedChainOnBusinessPath,
                    businessPathRequestStatus, failure);
        }
    }

    /** 装配可见性：数量 + 是否真的看到了上下文 + 判定方式。 */
    private record RegistrationVisibility(int count, boolean contextVisible, String strategy) {
    }

    /** 只记录状态码/响应头/正文的响应替身；其余方法返回类型默认值，绝不抛异常。 */
    private static final class RecordingResponseHandler implements InvocationHandler {

        private final Map<String, String> headers = new LinkedHashMap<>();
        private final StringWriter body = new StringWriter();
        private final PrintWriter writer = new PrintWriter(body);
        private int status = 200;

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "setStatus" -> {
                    status = (Integer) args[0];
                    return null;
                }
                case "getStatus" -> {
                    return status;
                }
                case "setHeader", "addHeader" -> {
                    headers.put((String) args[0], String.valueOf(args[1]));
                    return null;
                }
                case "getHeader" -> {
                    return headers.get((String) args[0]);
                }
                case "containsHeader" -> {
                    return headers.containsKey((String) args[0]);
                }
                case "getWriter" -> {
                    return writer;
                }
                case "setContentType", "setCharacterEncoding", "flushBuffer" -> {
                    return null;
                }
                default -> {
                    return defaultValue(method.getReturnType());
                }
            }
        }

        int status() {
            return status;
        }
    }

    /** "放行"的唯一判据：过滤器把请求交给了后续链路。 */
    private static final class RecordingChain implements FilterChain {

        private boolean reached;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) {
            reached = true;
        }

        boolean reached() {
            return reached;
        }
    }
}
