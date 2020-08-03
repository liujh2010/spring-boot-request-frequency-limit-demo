package com.example.limitreq.filter;

import com.example.limitreq.annotation.RequestFrequencyLimit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RequestFrequencyLimitFilter implements Filter {
    @Autowired
    private WebApplicationContext applicationContext;
    private final static Object checkLock = new Object();
    private final static Object putLock = new Object();
    private final static Map<String, Integer> apiLimitFreqMap = new HashMap<>();
    private final static Map<RequestRecord, Long> limitList = new ConcurrentHashMap<>(3000);
    private volatile static long lastCheckTime;
    private volatile static boolean notChecking = true;
    private static int remoteHostHeadType = 0;

    @Value("${ip-request-limit.limit-list.check-cycle}")
    private static int checkCycle = 300000;

    @Value("${ip-request-limit.remote-host.head-type}")
    private static String remoteHostHeadTypeStr = "default";

    private class RequestRecord {
        public final String url;
        public final String ip;
        private final String hashStr;

        public RequestRecord(String url, String ip) {
            this.url = url;
            this.ip = ip;
            hashStr = url + ip;
        }

        @Override
        public int hashCode() {
            return hashStr.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof RequestRecord)) return false;
            RequestRecord record = (RequestRecord) obj;
            return record.url.equals(url) && record.ip.equals(ip);
        }
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        switch (remoteHostHeadTypeStr) {
            case "nginx":
                remoteHostHeadType = 1;
                break;
            case "cloudflare":
                remoteHostHeadType = 2;
                break;
            default:
                remoteHostHeadType = 0;
        }

        RequestMappingHandlerMapping mapping = applicationContext.getBean(RequestMappingHandlerMapping.class);
        Map<RequestMappingInfo, HandlerMethod> map = mapping.getHandlerMethods();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : map.entrySet()) {
            Set<String> urls = entry.getKey().getPatternsCondition().getPatterns();
            RequestFrequencyLimit limit = entry.getValue().getMethod().getAnnotation(RequestFrequencyLimit.class);
            if (limit != null) {
                register(urls, limit.value());
            }
        }
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        if (notChecking && (System.currentTimeMillis() - lastCheckTime) >= checkCycle) {
            if (checking()) {
                checkLimitList();
                lastCheckTime = System.currentTimeMillis();
                checked();
            }
        }

        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        String uri = request.getServletPath();
        Integer apiFrequency = getApiFrequency(uri);
        if (apiFrequency == null) {
            filterChain.doFilter(servletRequest, servletResponse);
        } else {
            String ip = getRemoteHost(request);
            RequestRecord record = new RequestRecord(uri, ip);
            Long lastRequestTime = getLastRequestTime(record);
            if (lastRequestTime != null && notExpired(lastRequestTime, apiFrequency)) {
                writeErrorResponse(response, lastRequestTime, apiFrequency);
            } else {
                response.addHeader("x-rate-limit-frequency", apiFrequency + "ms");
                filterChain.doFilter(servletRequest, servletResponse);
                updateLimitList(record, System.currentTimeMillis());
            }
        }
    }

    @Override
    public void destroy() {

    }

    private boolean checking() {
        synchronized (checkLock) {
            if (notChecking) {
                notChecking = false;
                return true;
            }
            return false;
        }
    }

    private void checked() {
        notChecking = true;
    }

    private Integer getApiFrequency(Object object) {
        return apiLimitFreqMap.get(object);
    }

    private void updateLimitList(RequestRecord record, Long lastRequestTime) {
        synchronized (putLock) {
            Long oldLastRequestTime = limitList.get(record);
            if (oldLastRequestTime == null || oldLastRequestTime < lastRequestTime) {
                limitList.put(record, lastRequestTime);
            }
        }
    }

    private Long getLastRequestTime(RequestRecord record) {
        return limitList.get(record);
    }

    private String getRemoteHost(HttpServletRequest request) {
        String host = "";
        switch (remoteHostHeadType) {
            case 1:
                host = request.getHeader("X-Real-IP");
                break;
            case 2:
                host = request.getHeader("CF-Connecting-IP");
                break;
            default:
                host = request.getRemoteHost();
        }
        return host;
    }

    private boolean notExpired(Long lastRequestTime, Integer frequency) {
        return (System.currentTimeMillis() - lastRequestTime) < frequency;
    }

    private void writeErrorResponse(HttpServletResponse response, Long lastRequestTime, Integer apiFrequency) {
        PrintWriter out = null;
        try {
            response.addHeader("retry-after", (apiFrequency - (System.currentTimeMillis() - lastRequestTime)) + "ms");
            response.setStatus(429);
            out = response.getWriter();
            out.append("Too Many Requests! Try Again Later.");
        } catch (Exception e) {
            // log
        } finally {
            if (out != null) {
                out.close();
            }
        }
    }

    private void register(Set<String> urls, int frequency) {
        for (String url : urls) {
            apiLimitFreqMap.put(url, frequency);
        }
    }

    private void checkLimitList() {
        Long now = System.currentTimeMillis();
        Iterator<Map.Entry<RequestRecord, Long>> iterator = limitList.entrySet().iterator();
        for (; iterator.hasNext(); ) {
            Map.Entry<RequestRecord, Long> item = iterator.next();
            Long lastRequestTime = item.getValue();
            Integer frequency = apiLimitFreqMap.get(item.getKey().url);
            if ((now - lastRequestTime) >= frequency) {
                limitList.remove(item.getKey());
            }
        }
    }
}
