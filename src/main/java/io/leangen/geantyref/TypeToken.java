/*
 * License: Apache License, Version 2.0
 * See the LICENSE file in the root directory or at <a href="http://www.apache.org/licenses/LICENSE-2">apache.org</a>.
 */

package io.leangen.geantyref;

import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * Wrapper around {@link Type}.
 *
 * You can use this to create instances of Type for a type known at compile
 * time.
 *
 * For example, to get the Type that represents List&lt;String&gt;:
 * <code>Type listOfString = new TypeToken&lt;List&lt;String&gt;&gt;(){}.getType();</code>
 *
 * @param <T> The type represented by this TypeToken.
 * @author Wouter Coekaerts {@literal (wouter@coekaerts.be)}
 */
public abstract class TypeToken<T> {

    private final AnnotatedType type;

    /**
     * Constructs a type token.
     */
    protected TypeToken() {
        this.type = extractType();
    }

    private TypeToken(AnnotatedType type) {
        this.type = type;
    }

    /**
     * Gets type token for the given {@code Class} instance.
     *
     * @param type The class from which the token is created
     * @param <T> The generic type captured by the token
     *
     * @return TypeToken corresponding to the provided class
     */
    public static <T> TypeToken<T> get(Class<T> type) {
        return new TypeToken<T>(GenericTypeReflector.annotate(type)) {};
    }

    /**
     * Gets type token for the given {@code Type} instance.
     *
     * @param type The type from which the token is created
     *
     * @return TypeToken corresponding to the provided type
     */
    public static TypeToken<?> get(Type type) {
        return new TypeToken<Object>(GenericTypeReflector.annotate(type)) {};
    }

    public Type getType() {
        return type.getType();
    }

    public AnnotatedType getAnnotatedType() {
        return type;
    }

    public AnnotatedType getCanonicalType() {
        return GenericTypeReflector.toCanonical(type);
    }

    private AnnotatedType extractType() {
        AnnotatedType t = getClass().getAnnotatedSuperclass();
        if (!(t instanceof AnnotatedParameterizedType)) {
            throw new RuntimeException("Invalid TypeToken; must specify type parameters");
        }
        AnnotatedParameterizedType pt = (AnnotatedParameterizedType) t;
        if (((ParameterizedType) pt.getType()).getRawType() != TypeToken.class) {
            throw new RuntimeException("Invalid TypeToken; must directly extend TypeToken");
        }
        return pt.getAnnotatedActualTypeArguments()[0];
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof TypeToken && GenericTypeReflector.equals(type, ((TypeToken<?>) obj).type);
    }

    @Override
    public int hashCode() {
        return getType().hashCode();
    }
}
