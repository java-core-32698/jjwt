package io.jsonwebtoken.impl.security;

import io.jsonwebtoken.impl.lang.CheckedFunction;
import io.jsonwebtoken.impl.lang.Converters;
import io.jsonwebtoken.io.Encoders;
import io.jsonwebtoken.lang.Assert;
import io.jsonwebtoken.security.InvalidKeyException;
import io.jsonwebtoken.security.Jwk;

import java.math.BigInteger;
import java.security.Key;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;

abstract class AbstractFamilyJwkFactory<K extends Key, J extends Jwk<K>> implements FamilyJwkFactory<K, J> {

    protected static String encode(BigInteger bigInt) {
        byte[] unsigned = Converters.BIGINT_UNSIGNED_BYTES.applyTo(bigInt);
        return Encoders.BASE64URL.encode(unsigned);
    }

    private final String ktyValue;
    private final Class<K> keyType;

    AbstractFamilyJwkFactory(String ktyValue, Class<K> keyType) {
        this.ktyValue = Assert.hasText(ktyValue, "keyType argument cannot be null or empty.");
        this.keyType = Assert.notNull(keyType, "keyType class cannot be null.");
    }

    @Override
    public String getId() {
        return this.ktyValue;
    }

    @Override
    public boolean supports(JwkContext<?> ctx) {
        return supportsKey(ctx.getKey()) || supportsKeyValues(ctx);
    }

    protected boolean supportsKeyValues(JwkContext<?> ctx) {
        return this.ktyValue.equals(ctx.getType());
    }

    protected boolean supportsKey(Key key) {
        return this.keyType.isInstance(key);
    }

    protected K generateKey(final JwkContext<K> ctx, final CheckedFunction<KeyFactory, K> fn) {
        return generateKey(ctx, this.keyType, fn);
    }

    protected <T extends Key> T generateKey(final JwkContext<?> ctx, final Class<T> type, final CheckedFunction<KeyFactory, T> fn) {
        return new JcaTemplate(getId(), ctx.getProvider()).execute(KeyFactory.class, new CheckedFunction<KeyFactory, T>() {
            @Override
            public T apply(KeyFactory instance) throws Exception {
                try {
                    return fn.apply(instance);
                } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
                    String msg = "Unable to create " + type.getSimpleName() + " from JWK {" + ctx + "}: " + e.getMessage();
                    throw new InvalidKeyException(msg, e);
                }
            }
        });
    }

    @Override
    public final J createJwk(JwkContext<K> ctx) {
        Assert.notNull(ctx, "JwkContext argument cannot be null.");
        if (!supports(ctx)) { //should be asserted by caller, but assert just in case:
            String msg = "Unsupported JwkContext.";
            throw new IllegalArgumentException(msg);
        }
        K key = ctx.getKey();
        if (key != null) {
            ctx.setType(this.ktyValue);
            return createJwkFromKey(ctx);
        } else {
            return createJwkFromValues(ctx);
        }
    }

    //when called, ctx.getKey() is guaranteed to be non-null
    protected abstract J createJwkFromKey(JwkContext<K> ctx);

    //when called ctx.getType() is guaranteed to equal this.ktyValue
    protected abstract J createJwkFromValues(JwkContext<K> ctx);
}