package com.example.limitreq.util;

import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

import javax.servlet.http.HttpServletRequest;
import java.util.*;

public class RequestMappingMatcher {
    private final MultiValueMap<String, RequestMappingInfo> urlLookup = new LinkedMultiValueMap<>();
    private final Map<RequestMappingInfo, Boolean> mappingLookup = new LinkedHashMap<>();

    public void registerMapping(RequestMappingInfo mapping, boolean matching) {
        mappingLookup.put(mapping, matching);

        if (matching) {
            for (String url : mapping.getPatternsCondition().getPatterns()) {
                if (!this.isPattern(url)) {
                    urlLookup.add(url, mapping);
                }
            }
        }
    }

    public RequestMappingInfo lockupRequestMappingInfo(HttpServletRequest request) {
        List<RequestMappingInfo> matches = new ArrayList<>();
        String lookupPath = request.getServletPath();
        List<RequestMappingInfo> directPathMatches = this.getMappingInfosByUrl(lookupPath);

        if (directPathMatches != null) {
            this.addMatchingMapping(directPathMatches, matches, request);
        }

        if (matches.isEmpty()) {
            this.addMatchingMapping(this.getAllMappings(), matches, request);
        }

        if (!matches.isEmpty()) {
            RequestMappingInfo bastMatch = matches.get(0);

            if (matches.size() > 1) {
                Comparator<RequestMappingInfo> comparator = (info1, info2) -> info1.compareTo(info2, request);
                matches.sort(comparator);
                bastMatch = matches.get(0);
                RequestMappingInfo secondMatch = matches.get(0);

                if (comparator.compare(bastMatch, secondMatch) == 0) {
                    return null;
                }
            }

            return mappingLookup.get(bastMatch) ? bastMatch : null;
        } else {
            return null;
        }
    }

    private boolean isPattern(String path) {
        if (path == null) {
            return false;
        } else {
            boolean uriVar = false;

            for (int i = 0; i < path.length(); ++i) {
                char c = path.charAt(i);
                if (c == '*' || c == '?') {
                    return true;
                }

                if (c == '{') {
                    uriVar = true;
                } else if (c == '}' && uriVar) {
                    return true;
                }
            }

            return false;
        }
    }

    private void addMatchingMapping(Collection<RequestMappingInfo> mappings, List<RequestMappingInfo> matches, HttpServletRequest request) {
        Iterator<RequestMappingInfo> iterator = mappings.iterator();

        for (; iterator.hasNext(); ) {
            RequestMappingInfo mapping = iterator.next();

            if (mapping.getMatchingCondition(request) != null) {
                matches.add(mapping);
            }
        }

    }

    private Collection<RequestMappingInfo> getAllMappings() {
        return mappingLookup.keySet();
    }

    private List<RequestMappingInfo> getMappingInfosByUrl(String lockupPath) {
        return urlLookup.get(lockupPath);
    }
}

