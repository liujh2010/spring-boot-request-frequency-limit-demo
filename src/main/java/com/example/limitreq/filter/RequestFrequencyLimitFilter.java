package com.example.limitreq.filter;

import com.example.limitreq.annotation.RequestFrequencyLimit;
import com.example.limitreq.util.RequestMappingMatcher;
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
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RequestFrequencyLimitFilter implements Filter {
    private final static Object checkLock = new Object();
    private final static Object putLock = new Object();
    private final static Map<RequestMappingInfo, RequestLimitInfo> apiInfoFrequencyLookup = new HashMap<>();
    private final static Map<String, Integer> apiPathFrequencyLookup = new HashMap<>();
    private final static Map<RequestLimitRecord, Long> limitLookup = new ConcurrentHashMap<>(3000);
    private final static RequestMappingMatcher mappingMatcher = new RequestMappingMatcher();
    private volatile static long lastCheckTime;
    private volatile static boolean notChecking = true;
    private static int remoteHostHeadType = 0;

    @Autowired
    private WebApplicationContext applicationContext;

    @Value("${ip-request-limit.limit-list.check-cycle}")
    private static int checkCycle = 300000;

    @Value("${ip-request-limit.remote-host.head-type}")
    private static String remoteHostHeadTypeStr = "default";

    private class RequestLimitRecord {
        public final String url;
        public final String ip;
        private final int hashCode;

        public RequestLimitRecord(String url, String ip) {
            this.url = url;
            this.ip = ip;
            hashCode = (url + ip).hashCode();
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof RequestLimitRecord)) return false;
            RequestLimitRecord record = (RequestLimitRecord) obj;
            return record.url.equals(url) && record.ip.equals(ip);
        }
    }

    private class RequestLimitInfo {
        public final int frequency;
        public final String apiPath;

        public RequestLimitInfo(int frequency, String apiPath) {
            this.frequency = frequency;
            this.apiPath = apiPath;
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
            RequestMappingInfo info = entry.getKey();
            RequestFrequencyLimit limit = entry.getValue().getMethod().getAnnotation(RequestFrequencyLimit.class);

            if (limit != null) {
                this.register(info, limit.value());
                mappingMatcher.registerMapping(info);
            }
        }

    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        if (notChecking && (System.currentTimeMillis() - lastCheckTime) >= checkCycle) {
            if (this.checking()) {
                this.checkLimitLookup();
                lastCheckTime = System.currentTimeMillis();
                this.checked();
            }
        }

        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;
        RequestMappingInfo info = mappingMatcher.lockupRequestMappingInfo(request);

        if (info == null) {
            filterChain.doFilter(servletRequest, servletResponse);
        } else {
            RequestLimitInfo limitInfo = this.getApiFrequency(info);
            RequestLimitRecord record = new RequestLimitRecord(limitInfo.apiPath, this.getRemoteHost(request));
            Long lastRequestTime = this.getLastRequestTime(record);

            if (lastRequestTime != null && this.notExpired(lastRequestTime, limitInfo.frequency)) {
                this.writeErrorResponse(response, lastRequestTime, limitInfo.frequency);
            } else {
                response.addHeader("x-rate-limit-frequency", limitInfo.frequency + "ms");
                filterChain.doFilter(servletRequest, servletResponse);
                this.updateLimitList(record, System.currentTimeMillis());
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

    private RequestLimitInfo getApiFrequency(Object object) {
        return apiInfoFrequencyLookup.get(object);
    }

    private void updateLimitList(RequestLimitRecord record, Long lastRequestTime) {
        synchronized (putLock) {
            Long oldLastRequestTime = limitLookup.get(record);

            if (oldLastRequestTime == null || oldLastRequestTime < lastRequestTime) {
                limitLookup.put(record, lastRequestTime);
            }
        }
    }

    private Long getLastRequestTime(RequestLimitRecord record) {
        return limitLookup.get(record);
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

    private boolean notExpired(Long lastRequestTime, int frequency) {
        return (System.currentTimeMillis() - lastRequestTime) < frequency;
    }

    private void writeErrorResponse(HttpServletResponse response, Long lastRequestTime, int apiFrequency) {
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

    private void register(RequestMappingInfo info, int frequency) {
        String apiDirectPath = info.getPatternsCondition().getPatterns().iterator().next();
        apiInfoFrequencyLookup.put(info, new RequestLimitInfo(frequency, apiDirectPath));
        apiPathFrequencyLookup.put(apiDirectPath, frequency);
    }

    private void checkLimitLookup() {
        Long now = System.currentTimeMillis();
        Iterator<Map.Entry<RequestLimitRecord, Long>> iterator = limitLookup.entrySet().iterator();

        for (; iterator.hasNext(); ) {
            Map.Entry<RequestLimitRecord, Long> item = iterator.next();
            RequestLimitRecord record = item.getKey();
            Long lastRequestTime = item.getValue();
            Integer frequency = apiPathFrequencyLookup.get(record.url);
            if ((now - lastRequestTime) >= frequency) {
                limitLookup.remove(record);
            }
        }

    }
}
