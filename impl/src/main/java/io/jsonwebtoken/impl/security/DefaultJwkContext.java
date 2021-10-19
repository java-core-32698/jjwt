package io.jsonwebtoken.impl.security;

import io.jsonwebtoken.Identifiable;
import io.jsonwebtoken.impl.JwtMap;
import io.jsonwebtoken.impl.lang.BiFunction;
import io.jsonwebtoken.impl.lang.Converter;
import io.jsonwebtoken.impl.lang.Field;
import io.jsonwebtoken.lang.Assert;
import io.jsonwebtoken.lang.Collections;
import io.jsonwebtoken.lang.Objects;
import io.jsonwebtoken.lang.Strings;
import io.jsonwebtoken.security.MalformedKeyException;

import java.net.URI;
import java.security.Key;
import java.security.Provider;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DefaultJwkContext<K extends Key> implements JwkContext<K> {

    private static final Set<String> DEFAULT_PRIVATE_NAMES;
    private static final Map<String, Canonicalizer<?>> SETTERS;

    static {
        Set<String> set = new LinkedHashSet<>();
        set.addAll(DefaultRsaPrivateJwk.PRIVATE_NAMES);
        set.addAll(DefaultEcPrivateJwk.PRIVATE_NAMES);
        set.addAll(DefaultSecretJwk.PRIVATE_NAMES);
        DEFAULT_PRIVATE_NAMES = java.util.Collections.unmodifiableSet(set);

        @SuppressWarnings("RedundantTypeArguments")
        List<Canonicalizer<?>> fns = Collections.<Canonicalizer<?>>of(
            Canonicalizer.forField(AbstractJwk.ALG),
            Canonicalizer.forField(AbstractJwk.KID),
            Canonicalizer.forField(AbstractJwk.KEY_OPS),
            Canonicalizer.forField(AbstractJwk.KTY),
            Canonicalizer.forField(AbstractAsymmetricJwk.USE),
            Canonicalizer.forField(AbstractAsymmetricJwk.X5C),
            Canonicalizer.forField(AbstractAsymmetricJwk.X5T),
            Canonicalizer.forField(AbstractAsymmetricJwk.X5T_S256),
            Canonicalizer.forField(AbstractAsymmetricJwk.X5U)
        );
        Map<String, Canonicalizer<?>> s = new LinkedHashMap<>();
        for (Canonicalizer<?> fn : fns) {
            s.put(fn.getId(), fn);
        }
        SETTERS = java.util.Collections.unmodifiableMap(s);
    }

    private final Map<String, Object> values; // canonical values formatted per RFC requirements
    private final Map<String, Object> idiomaticValues; // the values map with any string/encoded values converted to Java type-safe values where possible
    private final Map<String, Object> redactedValues; // the values map with any sensitive/secret values redacted. Used in the toString implementation.
    private final Set<String> privateMemberNames; // names of values that should be redacted for toString output
    private K key;
    private PublicKey publicKey;
    private Provider provider;

    public DefaultJwkContext() {
        // For the default constructor case, we don't know how it will be used or what values will be populated,
        // so we can't know ahead of time what the sensitive data is.  As such, for security reasons, we assume all
        // the known private names for all supported algorithms in case it is used for any of them:
        this(DEFAULT_PRIVATE_NAMES);
    }

    public DefaultJwkContext(Set<String> privateMemberNames) {
        this.privateMemberNames = Assert.notEmpty(privateMemberNames, "privateMemberNames cannot be null or empty.");
        this.values = new LinkedHashMap<>();
        this.idiomaticValues = new LinkedHashMap<>();
        this.redactedValues = new LinkedHashMap<>();
    }

    public DefaultJwkContext(Set<String> privateMemberNames, JwkContext<?> other) {
        this(privateMemberNames, other, true);
    }

    public DefaultJwkContext(Set<String> privateMemberNames, JwkContext<?> other, K key) {
        //if the key is null or a PublicKey, we don't want to redact - we want to fully remove the items that are
        //private names (public JWKs should never contain any private key fields, even if redacted):
        this(privateMemberNames, other, (key == null || key instanceof PublicKey));
        this.key = Assert.notNull(key, "Key cannot be null.");
    }

    private DefaultJwkContext(Set<String> privateMemberNames, JwkContext<?> other, boolean removePrivate) {
        this.privateMemberNames = Assert.notEmpty(privateMemberNames, "privateMemberNames cannot be null or empty.");
        Assert.notNull(other, "JwkContext cannot be null.");
        Assert.isInstanceOf(DefaultJwkContext.class, other, "JwkContext must be a DefaultJwkContext instance.");
        DefaultJwkContext<?> src = (DefaultJwkContext<?>) other;
        this.provider = other.getProvider();
        this.values = new LinkedHashMap<>(src.values);
        this.idiomaticValues = new LinkedHashMap<>(src.idiomaticValues);
        this.redactedValues = new LinkedHashMap<>(src.redactedValues);
        if (removePrivate) {
            for (String name : this.privateMemberNames) {
                remove(name);
            }
        }
    }

    protected Object nullSafePut(String name, Object value) {
        if (JwtMap.isReduceableToNull(value)) {
            return remove(name);
        } else {
            Object redactedValue = this.privateMemberNames.contains(name) ? AbstractJwk.REDACTED_VALUE : value;
            this.redactedValues.put(name, redactedValue);
            this.idiomaticValues.put(name, value);
            return this.values.put(name, value);
        }
    }

    @Override
    public Object put(String name, Object value) {
        name = Assert.notNull(Strings.clean(name), "JWK member name cannot be null or empty.");
        if (value instanceof String) {
            value = Strings.clean((String) value);
        } else if (Objects.isArray(value) && !value.getClass().getComponentType().isPrimitive()) {
            value = Collections.arrayToList(value);
        }
        return idiomaticPut(name, value);
    }

    // ensures that if a property name matches an RFC-specified name, that value can be represented
    // as an idiomatic type-safe Java value in addition to the canonical RFC/encoded value.
    private Object idiomaticPut(String name, Object value) {
        assert name != null; //asserted by caller.
        Canonicalizer<?> fn = SETTERS.get(name);
        if (fn != null) { //Setting a JWA-standard property - let's ensure we can represent it idiomatically:
            return fn.apply(this, value);
        } else { //non-standard/custom property:
            return nullSafePut(name, value);
        }
    }

    @Override
    public void putAll(Map<? extends String, ?> m) {
        Assert.notEmpty(m, "JWK values cannot be null or empty.");
        for (Map.Entry<? extends String, ?> entry : m.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public Object remove(Object key) {
        this.redactedValues.remove(key);
        this.idiomaticValues.remove(key);
        return this.values.remove(key);
    }

    @Override
    public int size() {
        return this.values.size();
    }

    @Override
    public boolean isEmpty() {
        return this.values.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return this.values.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return this.values.containsValue(value);
    }

    @Override
    public Object get(Object key) {
        return this.values.get(key);
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException("Cannot clear JwkContext objects.");
    }

    @Override
    public Set<String> keySet() {
        return this.values.keySet();
    }

    @Override
    public Collection<Object> values() {
        return this.values.values();
    }

    @Override
    public Set<Map.Entry<String, Object>> entrySet() {
        return this.values.entrySet();
    }

    @Override
    public String getAlgorithm() {
        return (String) this.values.get(AbstractJwk.ALG.getId());
    }

    @Override
    public JwkContext<K> setAlgorithm(String algorithm) {
        put(AbstractJwk.ALG.getId(), algorithm);
        return this;
    }

    @Override
    public String getId() {
        return (String) this.values.get(AbstractJwk.KID.getId());
    }

    @Override
    public JwkContext<K> setId(String id) {
        put(AbstractJwk.KID.getId(), id);
        return this;
    }

    @Override
    public Set<String> getOperations() {
        //noinspection unchecked
        return (Set<String>) this.idiomaticValues.get(AbstractJwk.KEY_OPS.getId());
    }

    @Override
    public JwkContext<K> setOperations(Set<String> ops) {
        put(AbstractJwk.KEY_OPS.getId(), ops);
        return this;
    }

    @Override
    public String getType() {
        return (String) this.values.get(AbstractJwk.KTY.getId());
    }

    @Override
    public JwkContext<K> setType(String type) {
        put(AbstractJwk.KTY.getId(), type);
        return this;
    }

    @Override
    public String getPublicKeyUse() {
        return (String) this.values.get(AbstractAsymmetricJwk.USE.getId());
    }

    @Override
    public JwkContext<K> setPublicKeyUse(String use) {
        put(AbstractAsymmetricJwk.USE.getId(), use);
        return this;
    }

    @Override
    public List<X509Certificate> getX509CertificateChain() {
        //noinspection unchecked
        return (List<X509Certificate>) this.idiomaticValues.get(AbstractAsymmetricJwk.X5C.getId());
    }

    @Override
    public JwkContext<K> setX509CertificateChain(List<X509Certificate> x5c) {
        put(AbstractAsymmetricJwk.X5C.getId(), x5c);
        return this;
    }

    @Override
    public byte[] getX509CertificateSha1Thumbprint() {
        return (byte[]) this.idiomaticValues.get(AbstractAsymmetricJwk.X5T.getId());
    }

    @Override
    public JwkContext<K> setX509CertificateSha1Thumbprint(byte[] x5t) {
        put(AbstractAsymmetricJwk.X5T.getId(), x5t);
        return this;
    }

    @Override
    public byte[] getX509CertificateSha256Thumbprint() {
        return (byte[]) this.idiomaticValues.get(AbstractAsymmetricJwk.X5T_S256.getId());
    }

    @Override
    public JwkContext<K> setX509CertificateSha256Thumbprint(byte[] x5ts256) {
        put(AbstractAsymmetricJwk.X5T_S256.getId(), x5ts256);
        return this;
    }

    @Override
    public URI getX509Url() {
        return (URI) this.idiomaticValues.get(AbstractAsymmetricJwk.X5U.getId());
    }

    @Override
    public JwkContext<K> setX509Url(URI url) {
        put(AbstractAsymmetricJwk.X5U.getId(), url);
        return this;
    }

    @Override
    public K getKey() {
        return this.key;
    }

    @Override
    public JwkContext<K> setKey(K key) {
        this.key = key;
        return this;
    }

    @Override
    public PublicKey getPublicKey() {
        return this.publicKey;
    }

    @Override
    public JwkContext<K> setPublicKey(PublicKey publicKey) {
        this.publicKey = publicKey;
        return this;
    }

    @Override
    public Provider getProvider() {
        return this.provider;
    }

    @Override
    public JwkContext<K> setProvider(Provider provider) {
        this.provider = provider;
        return this;
    }

    @Override
    public Set<String> getPrivateMemberNames() {
        return this.privateMemberNames;
    }

    @Override
    public int hashCode() {
        return this.values.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Map) {
            return this.values.equals(obj);
        }
        return false;
    }

    @Override
    public String toString() {
        return this.redactedValues.toString();
    }

    private static class Canonicalizer<T> implements BiFunction<DefaultJwkContext<?>, Object, T>, Identifiable {

        private final String id;
        private final String title;
        private final Converter<T, Object> converter;

        public static <T> Canonicalizer<T> forField(Field<T> field) {
            return new Canonicalizer<>(field);
        }

        public Canonicalizer(Field<T> field) {
            this.id = field.getId();
            this.title = field.getName();
            this.converter = field.getConverter();
        }

        @Override
        public String getId() {
            return this.id;
        }

        @Override
        public T apply(DefaultJwkContext<?> ctx, Object rawValue) {

            //noinspection unchecked
            final T previousValue = (T)ctx.idiomaticValues.get(id);

            if (JwtMap.isReduceableToNull(rawValue)) {
                ctx.remove(id);
                return previousValue;
            }

            T idiomaticValue; // preferred Java format
            Object canonicalValue; //as required by the RFC
            try {
                idiomaticValue = converter.applyFrom(rawValue);
                canonicalValue = converter.applyTo(idiomaticValue);
            } catch (Exception e) {
                Object sval = ctx.privateMemberNames.contains(id) ? AbstractJwk.REDACTED_VALUE : rawValue;
                String msg = "Invalid JWK '" + id + "' (" + title + ") value [" + sval + "]: " + e.getMessage();
                throw new MalformedKeyException(msg, e);
            }
            ctx.nullSafePut(id, canonicalValue);
            ctx.idiomaticValues.put(id, idiomaticValue);
            return previousValue;
        }
    }
}