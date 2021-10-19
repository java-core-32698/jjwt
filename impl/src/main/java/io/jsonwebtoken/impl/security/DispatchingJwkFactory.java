package io.jsonwebtoken.impl.security;

import io.jsonwebtoken.lang.Assert;
import io.jsonwebtoken.lang.Strings;
import io.jsonwebtoken.security.Jwk;
import io.jsonwebtoken.security.UnsupportedKeyException;

import java.security.Key;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

class DispatchingJwkFactory implements JwkFactory<Key, Jwk<Key>> {

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Collection<FamilyJwkFactory<Key, ?>> createDefaultFactories() {
        List families = new ArrayList<>(3);
        families.add(new SecretJwkFactory());
        families.add(new AsymmetricJwkFactory(EcPublicJwkFactory.DEFAULT_INSTANCE, new EcPrivateJwkFactory()));
        families.add(new AsymmetricJwkFactory(RsaPublicJwkFactory.DEFAULT_INSTANCE, new RsaPrivateJwkFactory()));
        return families;
    }
    private static final Collection<FamilyJwkFactory<Key, ?>> DEFAULT_FACTORIES = createDefaultFactories();
    static final JwkFactory<Key, Jwk<Key>> DEFAULT_INSTANCE = new DispatchingJwkFactory();

    private final Collection<FamilyJwkFactory<Key, ?>> factories;

    DispatchingJwkFactory() {
        this(DEFAULT_FACTORIES);
    }

    @SuppressWarnings("unchecked")
    DispatchingJwkFactory(Collection<? extends FamilyJwkFactory<?, ?>> factories) {
        Assert.notEmpty(factories, "FamilyJwkFactory collection cannot be null or empty.");
        this.factories = new ArrayList<>(factories.size());
        for (FamilyJwkFactory<?, ?> factory : factories) {
            if (!Strings.hasText(factory.getId())) {
                String msg = "FamilyJwkFactory instance of type " + factory.getClass().getName() + " does not " +
                    "have a required algorithm family id (factory.getFactoryId() cannot be null or empty).";
                throw new IllegalArgumentException(msg);
            }
            this.factories.add((FamilyJwkFactory<Key, ?>) factory);
        }
    }

    @Override
    public Jwk<Key> createJwk(JwkContext<Key> ctx) {

        Assert.notNull(ctx, "JwkContext cannot be null.");

        final Key key = ctx.getKey();
        final String kty = Strings.clean(ctx.getType());

        if (key == null && kty == null) {
            String msg = "Either a Key instance or a '" + AbstractJwk.KTY + "' value is required to create a JWK.";
            throw new IllegalArgumentException(msg);
        }

        for (FamilyJwkFactory<Key, ?> factory : this.factories) {
            if (factory.supports(ctx)) {
                String algFamilyId = Assert.hasText(factory.getId(), "factory id cannot be null or empty.");
                if (kty == null) {
                    ctx.setType(algFamilyId); //ensure the kty is available for the rest of the creation process
                }
                return factory.createJwk(ctx);
            }
        }

        // if nothing has been returned at this point, no factory supported the JwkContext, so that's an error:
        String reason;
        if (key != null) {
            reason = "key of type " + key.getClass().getName();
        } else {
            reason = "kty value '" + kty + "'";
        }

        String msg = "Unable to create JWK for unrecognized " + reason + ": there is " +
            "no known JWK Factory capable of creating JWKs for this key type.";
        throw new UnsupportedKeyException(msg);
    }
}