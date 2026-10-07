package org.ruoyi.system.aiidentity;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Reuses identity facts only for one read request. Authorization transactions and
 * workers always read fresh facts; ACL epochs, barriers and permits never use this cache.
 * The Servlet request owns the values, so pooled threads cannot retain them.
 */
public final class RequestScopedAiFacts {
    private static final String ATTRIBUTE = RequestScopedAiFacts.class.getName();

    private RequestScopedAiFacts() { }

    public static <T> T read(Object source, Object factKey, Supplier<T> loader) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return loader.get();
        }
        HttpServletRequest request = attributes.getRequest();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            request.removeAttribute(ATTRIBUTE);
            return loader.get();
        }
        if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())) {
            return loader.get();
        }
        synchronized (request) {
            Facts facts;
            if (request.getAttribute(ATTRIBUTE) instanceof Facts existing) {
                facts = existing;
            } else {
                facts = new Facts();
                request.setAttribute(ATTRIBUTE, facts);
            }
            return facts.read(new Key(source, factKey), loader);
        }
    }

    private record Key(Object source, Object factKey) { }

    private static final class Facts {
        private final Map<Key, Object> values = new HashMap<>();

        @SuppressWarnings("unchecked")
        private <T> T read(Key key, Supplier<T> loader) {
            if (values.containsKey(key)) {
                return (T) values.get(key);
            }
            T value = loader.get();
            values.put(key, value);
            return value;
        }
    }
}
