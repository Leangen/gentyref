/*
 * License: Apache License, Version 2.0
 * See the LICENSE file in the root directory or at <a href="http://www.apache.org/licenses/LICENSE-2">apache.org</a>.
 */

package io.leangen.geantyref;

import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.AnnotatedTypeVariable;
import java.lang.reflect.AnnotatedWildcardType;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.Arrays.stream;

/**
 * Utility class for doing reflection on types.
 *
 * @author Wouter Coekaerts {@literal (wouter@coekaerts.be)}
 * @author Bojan Tomic {@literal (veggen@gmail.com)}
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class GenericTypeReflector {

    private static final WildcardType UNBOUND_WILDCARD = new WildcardTypeImpl(new Type[]{Object.class}, new Type[]{});

    private static final Map<Class<?>, Class<?>> BOX_TYPES;

    static {
        Map<Class<?>, Class<?>> boxTypes = new HashMap<>();
        boxTypes.put(boolean.class, Boolean.class);
        boxTypes.put(byte.class, Byte.class);
        boxTypes.put(char.class, Character.class);
        boxTypes.put(double.class, Double.class);
        boxTypes.put(float.class, Float.class);
        boxTypes.put(int.class, Integer.class);
        boxTypes.put(long.class, Long.class);
        boxTypes.put(short.class, Short.class);
        boxTypes.put(void.class, Void.class);
        BOX_TYPES = Collections.unmodifiableMap(boxTypes);
    }

    /**
     * Returns the erasure of the given type.
     */
    public static Class<?> erase(Type type) {
        if (type instanceof Class) {
            return (Class<?>)type;
        } else if (type instanceof ParameterizedType) {
            return (Class<?>)((ParameterizedType) type).getRawType();
        } else if (type instanceof TypeVariable) {
            TypeVariable<?> tv = (TypeVariable<?>)type;
            if (tv.getBounds().length == 0)
                return Object.class;
            else
                return erase(tv.getBounds()[0]);
        } else if (type instanceof GenericArrayType) {
            GenericArrayType aType = (GenericArrayType) type;
            return GenericArrayTypeImpl.createArrayType(erase(aType.getGenericComponentType()));
        } else if (type instanceof WildcardType) {
            WildcardType wildcardType = (WildcardType) type;
            Type[] lowerBounds = wildcardType.getLowerBounds();
            return erase(lowerBounds.length > 0 ? lowerBounds[0] : wildcardType.getUpperBounds()[0]);
        } else if (type instanceof CaptureType) {
            CaptureType captureType = (CaptureType) type;
            Type[] lowerBounds = captureType.getLowerBounds();
            return erase(lowerBounds.length > 0 ? lowerBounds[0] : captureType.getUpperBounds()[0]);
        } else {
            throw new RuntimeException("not supported: " + type.getClass());
        }
    }

    @SuppressWarnings("SuspiciousMethodCalls")
    public static Type box(Type type) {
        Class<?> boxed = BOX_TYPES.get(type);
        return boxed != null ? boxed : type;
    }

    @SuppressWarnings("SuspiciousMethodCalls")
    public static boolean isBoxType(Type type) {
        return BOX_TYPES.containsValue(type);
    }

    public static boolean isFullyBound(Type type) {
        if (type instanceof Class) {
            return true;
        }
        if (type instanceof ParameterizedType) {
            return Arrays.stream(((ParameterizedType) type).getActualTypeArguments()).allMatch(GenericTypeReflector::isFullyBound);
        }
        if (type instanceof GenericArrayType) {
            return isFullyBound(((GenericArrayType) type).getGenericComponentType());
        }
        return false;
    }

    /**
     * Maps type parameters in a type to their values.
     * @param toMapType Type possibly containing type arguments
     * @param typeAndParams must be either ParameterizedType, or (in case there are no type arguments, or it's a raw type) Class
     * @return toMapType, but with type parameters from typeAndParams replaced.
     */
    private static AnnotatedType mapTypeParameters(AnnotatedType toMapType, AnnotatedType typeAndParams) {
        return mapTypeParameters(toMapType, typeAndParams, VarMap.MappingMode.EXACT);
    }

    private static AnnotatedType mapTypeParameters(AnnotatedType toMapType, AnnotatedType typeAndParams, VarMap.MappingMode mappingMode) {
        if (isMissingTypeParameters(typeAndParams.getType())) {
            return new AnnotatedTypeImpl(erase(toMapType.getType()), toMapType.getAnnotations());
        } else {
            VarMap varMap = new VarMap();
            AnnotatedType handlingTypeAndParams = typeAndParams;
            while(handlingTypeAndParams instanceof AnnotatedParameterizedType) {
                AnnotatedParameterizedType pType = (AnnotatedParameterizedType)handlingTypeAndParams;
                Class<?> clazz = (Class<?>)((ParameterizedType) pType.getType()).getRawType(); // getRawType should always be Class
                TypeVariable<?>[] vars = clazz.getTypeParameters();
                varMap.addAll(vars, pType.getAnnotatedActualTypeArguments());
                Type owner = ((ParameterizedType) pType.getType()).getOwnerType();
                handlingTypeAndParams = owner == null ? null : annotate(owner);
            }
            return varMap.map(toMapType, mappingMode);
        }
    }

    public static AnnotatedType resolveExactType(AnnotatedType unresolved, AnnotatedType typeAndParams) {
        return resolveType(unresolved, expandGenerics(typeAndParams), VarMap.MappingMode.EXACT);
    }

    public static Type resolveExactType(Type unresolved, Type typeAndParams) {
        return resolveType(annotate(unresolved), annotate(typeAndParams, true), VarMap.MappingMode.EXACT).getType();
    }

    public static AnnotatedType resolveType(AnnotatedType unresolved, AnnotatedType typeAndParams) {
        return resolveType(unresolved, expandGenerics(typeAndParams), VarMap.MappingMode.ALLOW_INCOMPLETE);
    }

    public static Type resolveType(Type unresolved, Type typeAndParams) {
        return resolveType(annotate(unresolved), annotate(typeAndParams, true), VarMap.MappingMode.ALLOW_INCOMPLETE).getType();
    }

    private static AnnotatedType resolveType(AnnotatedType unresolved, AnnotatedType typeAndParams, VarMap.MappingMode mappingMode) {
        if (unresolved instanceof AnnotatedParameterizedType) {
            AnnotatedParameterizedType parameterizedType = (AnnotatedParameterizedType) unresolved;
            AnnotatedType[] params = stream(parameterizedType.getAnnotatedActualTypeArguments())
                    .map(p -> resolveType(p, typeAndParams, mappingMode))
                    .toArray(AnnotatedType[]::new);
            return replaceParameters(parameterizedType, params);
        }
        if (unresolved instanceof AnnotatedWildcardType) {
            AnnotatedType[] lower = stream(((AnnotatedWildcardType) unresolved).getAnnotatedLowerBounds())
                    .map(b -> resolveType(b, typeAndParams, mappingMode))
                    .toArray(AnnotatedType[]::new);
            AnnotatedType[] upper = stream(((AnnotatedWildcardType) unresolved).getAnnotatedUpperBounds())
                    .map(b -> resolveType(b, typeAndParams, mappingMode))
                    .toArray(AnnotatedType[]::new);
            return new AnnotatedWildcardTypeImpl((WildcardType) unresolved.getType(), unresolved.getAnnotations(), lower, upper);
        }
        if (unresolved instanceof AnnotatedTypeVariable) {
            TypeVariable<?> var = (TypeVariable<?>) unresolved.getType();
            if (var.getGenericDeclaration() instanceof Class) {
                //noinspection unchecked
                AnnotatedType resolved = getTypeParameter(typeAndParams, (TypeVariable<? extends Class<?>>) var);
                if (resolved != null) {
                    return updateAnnotations(resolved, unresolved.getAnnotations());
                }
            }
            if (mappingMode.equals(VarMap.MappingMode.ALLOW_INCOMPLETE)) {
                return unresolved;
            }
            throw new IllegalArgumentException("Variable " + var.getName() + " is not declared by the given type "
                    + typeAndParams.getType().getTypeName() + " or its super types");
        }
        if (unresolved instanceof AnnotatedArrayType) {
            AnnotatedType componentType = resolveType(
                    ((AnnotatedArrayType) unresolved).getAnnotatedGenericComponentType(), typeAndParams, mappingMode);
            return new AnnotatedArrayTypeImpl(TypeFactory.arrayOf(componentType.getType()), unresolved.getAnnotations(), componentType);
        }
        //TODO Deal with CaptureType somehow...
        return unresolved;
    }

    /**
     * Checks if the given type is a class that is supposed to have type parameters, but doesn't.
     * In other words, if it's a really raw type.
     */
    public static boolean isMissingTypeParameters(Type type) {
        if (type instanceof Class) {
            Class<?> clazz = (Class<?>) type;
            if (Modifier.isStatic(clazz.getModifiers())) {
                return clazz.getTypeParameters().length != 0;
            }
            for (Class<?> enclosing = clazz; enclosing != null; enclosing = enclosing.getEnclosingClass()) {
                if (enclosing.getTypeParameters().length != 0)
                    return true;
            }
            return false;
        } else if (type instanceof ParameterizedType) {
            return false;
        } else {
            throw new AssertionError("Unexpected type " + type.getClass());
        }
    }

    /**
     * Returns a type representing the class, with all type parameters the unbound wildcard ("?").
     * For example, {@code addWildcardParameters(Map.class)} returns a type representing {@code Map<?,?>}.
     * @return <ul>
     * <li>If clazz is a class or interface without type parameters, clazz itself is returned.</li>
     * <li>If clazz is a class or interface with type parameters, an instance of ParameterizedType is returned.</li>
     * <li>if clazz is an array type, an array type is returned with unbound wildcard parameters added in the the component type.
     * </ul>
     */
    public static Type addWildcardParameters(Class<?> clazz) {
        if (clazz.isArray()) {
            return GenericArrayTypeImpl.createArrayType(addWildcardParameters(clazz.getComponentType()));
        } else if (isMissingTypeParameters(clazz)) {
            TypeVariable<?>[] vars = clazz.getTypeParameters();
            Type[] arguments = new Type[vars.length];
            Arrays.fill(arguments, UNBOUND_WILDCARD);
            Type owner = clazz.getDeclaringClass() == null ? null : addWildcardParameters(clazz.getDeclaringClass());
            return new ParameterizedTypeImpl(clazz, arguments, owner);
        } else {
            return clazz;
        }
    }

    /**
     * The equivalent of {@link #getExactSuperType(Type, Class)} but works with {@link AnnotatedType}s
     *
     * @param subType The type whose supertype is to be searched for
     * @param searchSuperClass The class of the supertype to search for
     *
     * @return The annotated type representing {@code searchSuperClass} with type parameters from {@code subType}
     */
    public static AnnotatedType getExactSuperType(AnnotatedType subType, Class<?> searchSuperClass) {
        if (subType instanceof AnnotatedParameterizedType || subType.getType() instanceof Class || subType instanceof AnnotatedArrayType) {
            Class<?> superClass = erase(subType.getType());

            if (searchSuperClass == superClass) {
                return subType;
            }

            if (!searchSuperClass.isAssignableFrom(superClass)) {
                return null;
            }
        }

        for (AnnotatedType superType: getExactDirectSuperTypes(subType)) {
            AnnotatedType result = getExactSuperType(superType, searchSuperClass);
            if (result != null)
                return result;
        }

        return null;
    }

    /**
     * Finds the most specific supertype of {@code subType} whose erasure is {@code searchSuperClass}.
     * In other words, returns a type representing the class {@code searchSuperClass} plus its exact type parameters in {@code subType}.
     *
     * <ul>
     * <li>Returns an instance of {@link ParameterizedType} if {@code searchSuperClass} is a real class or interface and {@code subType} has parameters for it</li>
     * <li>Returns an instance of {@link GenericArrayType} if {@code searchSuperClass} is an array type, and {@code subType} has type parameters for it</li>
     * <li>Returns an instance of {@link Class} if {@code subType} is a raw type, or has no type parameters for {@code searchSuperClass}</li>
     * <li>Returns null if {@code searchSuperClass} is not a superclass of {@code subType}.</li>
     * </ul>
     *
     * <p>For example, with {@code class StringList implements List<String>}, {@code getExactSuperType(StringList.class, Collection.class)}
     * returns a {@link ParameterizedType} representing {@code Collection<String>}.
     * </p>
     *
     * @param subType The type whose supertype is to be searched for
     * @param searchSuperClass The class of the supertype to search for
     *
     * @return The type representing {@code searchSuperClass} with type parameters from {@code subType}
     */
    public static Type getExactSuperType(Type subType, Class<?> searchSuperClass) {
        AnnotatedType superType = getExactSuperType(annotate(subType), searchSuperClass);
        return superType == null ? null : superType.getType();
    }

    /**
     * The equivalent of {@link #getExactSubType(Type, Class)} but works with {@link AnnotatedType}s
     *
     * @param superType The type whose subtype is to be searched for
     * @param searchSubClass The class of the subtype to search for
     *
     * @return The annotated type representing {@code searchSubClass} with type parameters from {@code superType}
     */
    public static AnnotatedType getExactSubType(AnnotatedType superType, Class<?> searchSubClass) {
        Type subType = searchSubClass;
        if (searchSubClass.getTypeParameters().length > 0) {
            subType = TypeFactory.parameterizedClass(searchSubClass, searchSubClass.getTypeParameters());
        }
        AnnotatedType annotatedSubType = annotate(subType);
        Class<?> rawSuperType = erase(superType.getType());
        if (searchSubClass.isArray() && superType instanceof AnnotatedArrayType) {
            if (rawSuperType.isAssignableFrom(searchSubClass)) {
                return AnnotatedArrayTypeImpl.createArrayType(
                        getExactSubType(((AnnotatedArrayType) superType).getAnnotatedGenericComponentType(), searchSubClass.getComponentType()),
                        new Annotation[0]);
            } else {
                return null;
            }
        }
        if (searchSubClass.getTypeParameters().length == 0) {
            return annotatedSubType;
        }
        if (!(superType instanceof AnnotatedParameterizedType)) {
            return annotate(searchSubClass);
        }
        AnnotatedParameterizedType parameterizedSuperType = (AnnotatedParameterizedType) superType;
        AnnotatedParameterizedType matched = (AnnotatedParameterizedType) getExactSuperType(annotatedSubType, rawSuperType);
        if (matched == null) return null;
        VarMap varMap = new VarMap();
        try {
            extractVariables(parameterizedSuperType, matched, searchSubClass, varMap);
            return varMap.map(annotatedSubType);
        } catch (UnresolvedTypeVariableException e) {
            return annotate(searchSubClass);
        } catch (IllegalArgumentException e) {
            return null; //Type shape did not match
        }
    }

    /**
     * Finds the most specific subtype of {@code superType} whose erasure is {@code searchSubClass}.
     * In other words, returns a type representing the class {@code searchSubClass} plus its exact type parameters in {@code superType},
     * if they are possible to resolve.
     *
     * <ul>
     * <li>Returns an instance of {@link AnnotatedParameterizedType} if {@code searchSubClass} is a real class or interface and {@code superType} has parameters for it</li>
     * <li>Returns an instance of {@link AnnotatedArrayType} if {@code searchSubClass} is an array type, and {@code superType} has type parameters for it</li>
     * <li>Returns an instance of {@link AnnotatedType} if {@code superType} is a raw type, or has no type parameters for {@code searchSubClass}</li>
     * <li>Returns null if {@code searchSubClass} is not a subclass of {@code superType}.</li>
     * </ul>
     *
     * <p>For example, with {@code getExactSubType(new TypeToken<List<String>>(){}.getAnnotatedType(), ArrayList.class)}
     * returns a {@link AnnotatedParameterizedType} representing {@code ArrayList<String>}.
     * </p>
     */
    public static Type getExactSubType(Type superType, Class<?> searchSubClass) {
        AnnotatedType resolvedSubtype = getExactSubType(annotate(superType), searchSubClass);
        return resolvedSubtype == null ? null : resolvedSubtype.getType();
    }

    /**
     * Gets the type parameter for a given type that is the value for a given type variable.
     * For example, with {@code class StringList implements List<String>},
     * {@code getTypeParameter(StringList.class, Collection.class.getTypeParameters()[0])}
     * returns {@code String}.
     *
     * @param type The type to inspect.
     * @param variable The type variable to find the value for.
     * @return The type parameter for the given variable. Or null if type is not a subtype of the
     * 	type that declares the variable, or if the variable isn't known (because of raw types).
     */
    public static AnnotatedType getTypeParameter(AnnotatedType type, TypeVariable<? extends Class<?>> variable) {
        Class<?> clazz = variable.getGenericDeclaration();
        AnnotatedType superType = getExactSuperType(type, clazz);
        if (superType instanceof AnnotatedParameterizedType) {
            int index = Arrays.asList(clazz.getTypeParameters()).indexOf(variable);
            AnnotatedType resolvedVarType = ((AnnotatedParameterizedType)superType).getAnnotatedActualTypeArguments()[index];
            return updateAnnotations(resolvedVarType, variable.getAnnotations());
        } else {
            return null;
        }
    }

    public static Type getTypeParameter(Type type, TypeVariable<? extends Class<?>> variable) {
        AnnotatedType typeParameter = getTypeParameter(annotate(type), variable);
        return typeParameter == null ? null : typeParameter.getType();
    }

    /**
     * Checks if the capture of subType is a subtype of superType
     */
    public static boolean isSuperType(Type superType, Type subType) {
        if (superType instanceof ParameterizedType || superType instanceof Class || superType instanceof GenericArrayType) {
            Class<?> superClass = erase(superType);
            AnnotatedType annotatedMappedSubType = getExactSuperType(capture(annotate(subType)), superClass);
            Type mappedSubType = annotatedMappedSubType == null ? null : annotatedMappedSubType.getType();
            if (mappedSubType == null) {
                return false;
            } else if (superType instanceof Class<?>) {
                return true;
            } else if (mappedSubType instanceof Class<?>) {
                // TODO treat supertype by being raw type differently ("supertype, but with warnings")
                return true; // class has no parameters, or it's a raw type
            } else if (mappedSubType instanceof GenericArrayType) {
                Type superComponentType = getArrayComponentType(superType);
                assert superComponentType != null;
                Type mappedSubComponentType = getArrayComponentType(mappedSubType);
                assert mappedSubComponentType != null;
                return isSuperType(superComponentType, mappedSubComponentType);
            } else {
                assert mappedSubType instanceof ParameterizedType;
                assert superType instanceof ParameterizedType;
                ParameterizedType pMappedSubType = (ParameterizedType) mappedSubType;
                assert pMappedSubType.getRawType() == superClass;
                ParameterizedType pSuperType = (ParameterizedType)superType;

                Type[] superTypeArgs = pSuperType.getActualTypeArguments();
                Type[] subTypeArgs = pMappedSubType.getActualTypeArguments();
                assert superTypeArgs.length == subTypeArgs.length;
                for (int i = 0; i < superTypeArgs.length; i++) {
                    if (! contains(superTypeArgs[i], subTypeArgs[i])) {
                        return false;
                    }
                }
                // params of the class itself match, so if the owner types are supertypes too, it's a supertype.
                return pSuperType.getOwnerType() == null || isSuperType(pSuperType.getOwnerType(), pMappedSubType.getOwnerType());
            }
        } else if (superType instanceof CaptureType) {
            if (superType.equals(subType))
                return true;
            for (Type lowerBound : ((CaptureType) superType).getLowerBounds()) {
                if (isSuperType(lowerBound, subType)) {
                    return true;
                }
            }
            return false;
//		} else if (superType instanceof GenericArrayType) {
//			return isArraySupertype(superType, subType);
        } else {
            throw new RuntimeException("Type not supported: " + superType.getClass());
        }
    }

    private static boolean isArraySupertype(Type arraySuperType, Type subType) {
        Type superTypeComponent = getArrayComponentType(arraySuperType);
        assert superTypeComponent != null;
        Type subTypeComponent = getArrayComponentType(subType);
        if (subTypeComponent == null) { // subType is not an array type
            return false;
        } else {
            return isSuperType(superTypeComponent, subTypeComponent);
        }
    }

    /**
     * If type is an array type, returns the annotated type of the component of the array.
     * Otherwise, returns null.
     */
    public static AnnotatedType getArrayComponentType(AnnotatedType type) {
        if (type.getType() instanceof Class) {
            Class<?> clazz = (Class<?>)type.getType();
            return new AnnotatedTypeImpl(clazz.getComponentType(), clazz.getAnnotations());
        } else if (type instanceof AnnotatedArrayType) {
            AnnotatedArrayType aType = (AnnotatedArrayType) type;
            return aType.getAnnotatedGenericComponentType();
        } else {
            return null;
        }
    }

    /**
     * If type is an array type, returns the type of the component of the array.
     * Otherwise, returns null.
     */
    public static Type getArrayComponentType(Type type) {
        AnnotatedType componentType = getArrayComponentType(annotate(type));
        return componentType == null ? null : componentType.getType();
    }

    private static boolean contains(Type containingType, Type containedType) {
        if (containingType instanceof WildcardType) {
            WildcardType wContainingType = (WildcardType)containingType;
            for (Type upperBound : wContainingType.getUpperBounds()) {
                if (! isSuperType(upperBound, containedType)) {
                    return false;
                }
            }
            for (Type lowerBound : wContainingType.getLowerBounds()) {
                if (! isSuperType(containedType, lowerBound)) {
                    return false;
                }
            }
            return true;
        } else {
            return containingType.equals(containedType);
        }
    }

    private static void extractVariables(AnnotatedParameterizedType resolvedTyped, AnnotatedParameterizedType unresolvedType, Class<?> declaringClass, VarMap variables) {
        for (int i = 0; i < resolvedTyped.getAnnotatedActualTypeArguments().length; i++) {
            final AnnotatedType unresolvedParam = unresolvedType.getAnnotatedActualTypeArguments()[i];
            final AnnotatedType resolvedParam = resolvedTyped.getAnnotatedActualTypeArguments()[i];
            final Type var = unresolvedParam.getType();
            if (var instanceof TypeVariable && ((TypeVariable<?>) var).getGenericDeclaration() == declaringClass) {
                variables.add(((TypeVariable<?>) var), resolvedParam);
            } else if (unresolvedParam instanceof AnnotatedParameterizedType) {
                if (!(resolvedParam instanceof AnnotatedParameterizedType) || !erase(unresolvedParam.getType()).equals(erase(resolvedParam.getType()))) {
                    throw new IllegalArgumentException("The provided types do not match in shape");
                }
                extractVariables((AnnotatedParameterizedType) resolvedParam, (AnnotatedParameterizedType) unresolvedParam, declaringClass, variables);
            }
        }
    }

    /**
     * Returns the direct supertypes of the given type. Resolves type parameters.
     */
    private static AnnotatedType[] getExactDirectSuperTypes(AnnotatedType type) {
        if (type instanceof AnnotatedParameterizedType || (type != null && type.getType() instanceof Class)) {
            Class<?> clazz;
            if (type instanceof AnnotatedParameterizedType) {
                clazz = (Class<?>)((ParameterizedType)type.getType()).getRawType();
            } else {
                // TODO primitive types?
                clazz = (Class<?>) type.getType();
                if (clazz.isArray()) {
                    return getArrayExactDirectSuperTypes(annotate(clazz));
                }
            }

            AnnotatedType[] superInterfaces = clazz.getAnnotatedInterfaces();
            AnnotatedType superClass = clazz.getAnnotatedSuperclass();

            // the only supertype of an interface without superinterfaces is Object
            if (superClass == null && superInterfaces.length == 0 && clazz.isInterface()) {
                return new AnnotatedType[] {new AnnotatedTypeImpl(Object.class)};
            }

            AnnotatedType[] result;
            int resultIndex;
            if (superClass == null) {
                result = new AnnotatedType[superInterfaces.length];
                resultIndex = 0;
            } else {
                result = new AnnotatedType[superInterfaces.length + 1];
                resultIndex = 1;
                result[0] = mapTypeParameters(superClass, type);
            }
            for (AnnotatedType superInterface : superInterfaces) {
                result[resultIndex++] = mapTypeParameters(superInterface, type);
            }

            return result;
        } else if (type instanceof AnnotatedTypeVariable) {
            AnnotatedTypeVariable tv = (AnnotatedTypeVariable) type;
            return tv.getAnnotatedBounds();
        } else if (type instanceof AnnotatedWildcardType) {
            // This should be a rare case: normally this wildcard is already captured.
            // But it does happen if the upper bound of a type variable contains a wildcard
            // TODO shouldn't upper bound of type variable have been captured too? (making this case impossible?)
            return ((AnnotatedWildcardType) type).getAnnotatedUpperBounds();
        } else if (type instanceof AnnotatedCaptureTypeImpl) {
            return ((AnnotatedCaptureTypeImpl)type).getAnnotatedUpperBounds();
        } else if (type instanceof AnnotatedArrayType) {
            return getArrayExactDirectSuperTypes(type);
        } else if (type == null) {
            throw new NullPointerException();
        } else {
            throw new RuntimeException("not implemented type: " + type);
        }
    }

    private static AnnotatedType[] getArrayExactDirectSuperTypes(AnnotatedType arrayType) {
        // see http://java.sun.com/docs/books/jls/third_edition/html/typesValues.html#4.10.3
        AnnotatedType typeComponent = getArrayComponentType(arrayType);

        AnnotatedType[] result;
        int resultIndex;
        if (typeComponent != null && typeComponent.getType() instanceof Class && ((Class<?>)typeComponent.getType()).isPrimitive()) {
            resultIndex = 0;
            result = new AnnotatedType[3];
        } else {
            AnnotatedType[] componentSupertypes = getExactDirectSuperTypes(typeComponent);
            result = new AnnotatedType[componentSupertypes.length + 3];
            for (resultIndex = 0; resultIndex < componentSupertypes.length; resultIndex++) {
                result[resultIndex] = AnnotatedArrayTypeImpl.createArrayType(componentSupertypes[resultIndex], new Annotation[0]);
            }
        }
        result[resultIndex++] = new AnnotatedTypeImpl(Object.class);
        result[resultIndex++] = new AnnotatedTypeImpl(Cloneable.class);
        result[resultIndex++] = new AnnotatedTypeImpl(Serializable.class);
        return result;
    }

    /**
     * Resolves the exact return type of the given method in the given type.
     * This may be different from {@code m.getAnnotatedReturnType()} when the method was declared in a superclass,
     * or {@code declaringType} has a type parameter that is used in the return type, or {@code declaringType} is a raw type.
     */
    public static AnnotatedType getExactReturnType(Method m, AnnotatedType declaringType) {
        return getReturnType(m, declaringType, VarMap.MappingMode.EXACT);
    }

    /**
     * Resolves the exact return type of the given method in the given type.
     * This may be different from {@code m.getGenericReturnType()} when the method was declared in a superclass,
     * or {@code declaringType} has a type parameter that is used in the return type, or {@code declaringType} is a raw type.
     */
    public static Type getExactReturnType(Method m, Type declaringType) {
        return getExactReturnType(m, annotate(declaringType)).getType();
    }

    /**
     * Resolves the return type of the given method in the given type. Any unresolvable variables will be kept
     * (in contrast to {@link #getExactReturnType(Method, AnnotatedType)}.
     * This may be different from {@code m.getAnnotatedReturnType()} when the method was declared in a superclass,
     * or {@code declaringType} has a type parameter that is used in the return type, or {@code declaringType} is a raw type.
     */
    public static AnnotatedType getReturnType(Method m, AnnotatedType declaringType) {
        return getReturnType(m, declaringType, VarMap.MappingMode.ALLOW_INCOMPLETE);
    }

    /**
     * Resolves the return type of the given method in the given type. Any unresolvable variables will be kept
     * (in contrast to {@link #getExactReturnType(Method, Type)}.
     * This may be different from {@code m.getGenericReturnType()} when the method was declared in a superclass,
     * or {@code declaringType} has a type parameter that is used in the return type, or {@code declaringType} is a raw type.
     */
    public static Type getReturnType(Method m, Type declaringType) {
        return getReturnType(m, annotate(declaringType)).getType();
    }

    private static AnnotatedType getReturnType(Method m, AnnotatedType declaringType, VarMap.MappingMode mappingMode) {
        AnnotatedType returnType = m.getAnnotatedReturnType();
        AnnotatedType exactDeclaringType = getExactSuperType(capture(declaringType), m.getDeclaringClass());
        if (exactDeclaringType == null) { // capture(type) is not a subtype of m.getDeclaringClass()
            throw new IllegalArgumentException("The method " + m + " is not a member of type " + declaringType);
        }
        return mapTypeParameters(returnType, exactDeclaringType, mappingMode);
    }

    /**
     * Resolves the exact type of the given field in the given type.
     * This may be different from {@code f.getAnnotatedType()} when the field was declared in a superclass,
     * or {@code declaringType} has a type parameter that is used in the type of the field, or {@code declaringType} is a raw type.
     */
    public static AnnotatedType getExactFieldType(Field f, AnnotatedType declaringType) {
        return getFieldType(f, declaringType, VarMap.MappingMode.EXACT);
    }

    /**
     * Resolves the exact type of the given field in the given type.
     * This may be different from {@code f.getGenericType()} when the field was declared in a superclass,
     * or {@code declaringType} has a type parameter that is used in the type of the field, or {@code declaringType} is a raw type.
     */
    public static Type getExactFieldType(Field f, Type type) {
        return getExactFieldType(f, annotate(type)).getType();
    }

    /**
     * Resolves the type of the given field in the given type. Any unresolvable variables will be kept
     * (in contrast to {@link #getExactFieldType(Field, AnnotatedType)}).
     * This may be different from {@code f.getAnnotatedType()} when the field was declared in a superclass,
     * or {@code declaringType} has a type parameter that is used in the type of the field, or {@code declaringType} is a raw type.
     */
    public static AnnotatedType getFieldType(Field f, AnnotatedType declaringType) {
        return getFieldType(f, declaringType, VarMap.MappingMode.ALLOW_INCOMPLETE);
    }

    /**
     * Resolves the type of the given field in the given type. Any unresolvable variables will be kept
     * (in contrast to {@link #getExactFieldType(Field, Type)}).
     * This may be different from {@code f.getGenericType()} when the field was declared in a superclass,
     * or {@code declaringType} has a type parameter that is used in the type of the field, or {@code declaringType} is a raw type.
     */
    public static Type getFieldType(Field f, Type type) {
        return getFieldType(f, annotate(type)).getType();
    }

    private static AnnotatedType getFieldType(Field f, AnnotatedType declaringType, VarMap.MappingMode mappingMode) {
        AnnotatedType returnType = f.getAnnotatedType();
        AnnotatedType exactDeclaringType = getExactSuperType(capture(declaringType), f.getDeclaringClass());
        if (exactDeclaringType == null) { // capture(type) is not a subtype of f.getDeclaringClass()
            throw new IllegalArgumentException("The field " + f + " is not a member of type " + declaringType);
        }
        return mapTypeParameters(returnType, exactDeclaringType, mappingMode);
    }

    /**
     * Resolves the exact annotated parameter types of the given method/constructor in the given type.
     * This may be different from {@code exe.getAnnotatedParameterTypes()} when the method was declared in a superclass,
     * or {@code declaringType} has a type parameter that is used in one of the parameters, or {@code declaringType} is a raw type.
     */
    public static AnnotatedType[] getExactParameterTypes(Executable exe, AnnotatedType declaringType) {
        return getParameterTypes(exe, declaringType, VarMap.MappingMode.EXACT);
    }

    /**
     * Resolves the exact parameter types of the given method/constructor in the given type.
     * This may be different from {@code exe.getParameterTypes()} when the method was declared in a superclass,
     * or {@code declaringType} has a type parameter that is used in one of the parameters, or {@code declaringType} is a raw type.
     */
    public static Type[] getExactParameterTypes(Executable exe, Type declaringType) {
        return stream(getExactParameterTypes(exe, annotate(declaringType))).map(AnnotatedType::getType).toArray(Type[]::new);
    }

    public static AnnotatedType[] getParameterTypes(Executable exe, AnnotatedType declaringType) {
        return getParameterTypes(exe, declaringType, VarMap.MappingMode.ALLOW_INCOMPLETE);
    }

    public static Type[] getParameterTypes(Executable exe, Type declaringType) {
        return stream(getParameterTypes(exe, annotate(declaringType))).map(AnnotatedType::getType).toArray(Type[]::new);
    }

    private static AnnotatedType[] getParameterTypes(Executable exe, AnnotatedType declaringType, VarMap.MappingMode mappingMode) {
        AnnotatedType[] parameterTypes = exe.getAnnotatedParameterTypes();
        AnnotatedType exactDeclaringType = getExactSuperType(capture(declaringType), exe.getDeclaringClass());
        if (exactDeclaringType == null) { // capture(type) is not a subtype of exe.getDeclaringClass()
            throw new IllegalArgumentException("The method/constructor " + exe + " is not a member of type " + declaringType);
        }

        AnnotatedType[] result = new AnnotatedType[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            result[i] = mapTypeParameters(parameterTypes[i], exactDeclaringType, mappingMode);
        }
        return result;
    }

    /**
     * Applies capture conversion to the given type.
     */
    public static AnnotatedType capture(AnnotatedType type) {
        if (type instanceof AnnotatedParameterizedType) {
            return capture((AnnotatedParameterizedType)type);
        } else {
            return type;
        }
    }
//
    /**
     * Applies capture conversion to the given type.
     * @see #capture(AnnotatedType)
     */
    public static AnnotatedParameterizedType capture(AnnotatedParameterizedType type) {
        // the map from parameters to their captured equivalent

        VarMap varMap = new VarMap();
        // list of CaptureTypes we've created but aren't fully initialized yet
        // we can only initialize them *after* we've fully populated varMap
        List<AnnotatedCaptureTypeImpl> toInit = new ArrayList<>();

        Class<?> clazz = (Class<?>)((ParameterizedType)type.getType()).getRawType();
        AnnotatedType[] arguments = type.getAnnotatedActualTypeArguments();
        TypeVariable<?>[] vars = clazz.getTypeParameters();
        AnnotatedType[] capturedArguments = new AnnotatedType[arguments.length];

        assert arguments.length == vars.length; // NICE throw an explaining exception

        for (int i = 0; i < arguments.length; i++) {
            AnnotatedType argument = arguments[i];
            if (argument instanceof AnnotatedWildcardType) {
                AnnotatedCaptureTypeImpl captured = new AnnotatedCaptureTypeImpl((AnnotatedWildcardType)argument, new AnnotatedTypeVariableImpl(vars[i]));
                argument = captured;
                toInit.add(captured);
            }
            capturedArguments[i] = argument;
            varMap.add(vars[i], argument);
        }
        for (AnnotatedCaptureTypeImpl captured : toInit) {
            captured.init(varMap);
        }
        ParameterizedType inner = (ParameterizedType) type.getType();
        AnnotatedType ownerType = (inner.getOwnerType() == null) ? null : capture(annotate(inner.getOwnerType()));
        Type[] rawArgs = stream(capturedArguments).map(AnnotatedType::getType).toArray(Type[]::new);
        ParameterizedType nn = new ParameterizedTypeImpl(clazz, rawArgs, ownerType == null ? null : ownerType.getType());
        return new AnnotatedParameterizedTypeImpl(nn, type.getAnnotations(), capturedArguments);
    }

    /**
     * Returns the display name of a Type.
     */
    public static String getTypeName(Type type) {
        if(type instanceof Class) {
            Class<?> clazz = (Class<?>) type;
            return clazz.isArray() ? (getTypeName(clazz.getComponentType()) + "[]") : clazz.getName();
        } else {
            return type.toString();
        }
    }

    /**
     * Returns list of classes and interfaces that are supertypes of the given type.
     * For example given this class:
     * {@code class {@literal Foo<A extends Number & Iterable<A>, B extends A>}}<br>
     * calling this method on type parameters {@code B} ({@code Foo.class.getTypeParameters()[1]})
     * returns a list containing {@code Number} and {@code Iterable}.
     * <p>
     * This is mostly useful if you get a type from one of the other methods in {@code GenericTypeReflector},
     * but you don't want to deal with all the different sorts of types,
     * and you are only really interested in concrete classes and interfaces.
     * </p>
     *
     * @return A List of classes, each of them a supertype of the given type.
     * 	If the given type is a class or interface itself, returns a List with just the given type.
     *  The list contains no duplicates, and is ordered in the order the upper bounds are defined on the type.
     */
    public static List<Class<?>> getUpperBoundClassAndInterfaces(Type type) {
        LinkedHashSet<Class<?>> result = new LinkedHashSet<>();
        buildUpperBoundClassAndInterfaces(type, result);
        return new ArrayList<>(result);
    }

    private static AnnotatedType annotate(Type type, boolean expandGenerics) {
        return annotate(type, expandGenerics, new HashMap<>());
    }

    /**
     * Recursively wraps a {@link Type} into an {@link AnnotatedType} using the annotations found on
     * the erasure classes.
     *
     * @param type Type to annotate
     * @return Type whose structure has been recursively annotated
     */
    public static AnnotatedType annotate(Type type) {
        return annotate(type, false);
    }

    /**
     * Recursively wraps a {@link Type} into an {@link AnnotatedType} using the annotations found on
     * the erasure classes, plus adding the provided annotations to the top level {@link Type} only.
     *
     * @param type Type to annotate
     * @return Type whose structure has been recursively annotated, plus the provided annotation added
     * at the top level
     *
     * <p>See {@link #annotate(Type)}</p>
     */
    public static AnnotatedType annotate(Type type, Annotation[] annotations) {
        return updateAnnotations(annotate(type), annotations);
    }

    /**
     * This is the method underlying both {@link #annotate(Type)} and {@link #annotate(Type, Annotation[])}.
     * If goes recursively through the structure of the provided {@link Type} wrapping all type parameters,
     * bounds etc encountered into {@link AnnotatedType}s using annotations found directly on the
     * corresponding erasure class, with a special treatment for {@link CaptureType} which can have
     * infinitely recursive structure by having itself as its upper bound.
     *
     * @param type The type to annotate
     * @param cache The cache for already encountered {@link CaptureType}s. Necessary because
     *       {@link CaptureType}s can have infinitely recursive structure.
     *
     * @return Type whose structure has been recursively annotated
     *
     * <p>See {@link #annotate(Type)}</p>
     * <p>See {@link #annotate(Type, Annotation[])}</p>
     * <p>See {@link CaptureCacheKey}</p>
     * <p>See {@link CaptureType}</p>
     */
    private static AnnotatedType annotate(Type type, boolean expandGenerics, Map<CaptureCacheKey, AnnotatedType> cache) {
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterized = (ParameterizedType) type;
            AnnotatedType[] params = new AnnotatedType[parameterized.getActualTypeArguments().length];
            for (int i = 0; i < params.length; i++) {
                AnnotatedType param = annotate(parameterized.getActualTypeArguments()[i], expandGenerics, cache);
                params[i] = updateAnnotations(param, erase(type).getTypeParameters()[i].getAnnotations());
            }
            return new AnnotatedParameterizedTypeImpl(parameterized, erase(type).getAnnotations(), params);
        }
        if (type instanceof CaptureType) {
            CaptureCacheKey key = new CaptureCacheKey(((CaptureType) type));
            if (cache.containsKey(key)) {
                return cache.get(key);
            }
            CaptureType capture = ((CaptureType) type);
            AnnotatedCaptureType annotatedCapture = new AnnotatedCaptureTypeImpl(capture,
                    ((AnnotatedWildcardType) annotate(capture.getWildcardType(), expandGenerics, cache)),
                    (AnnotatedTypeVariable) annotate(capture.getTypeVariable(), expandGenerics, cache));

            cache.put(new CaptureCacheKey(capture), annotatedCapture);
            AnnotatedType[] upperBounds = stream(capture.getUpperBounds())
                    .map(bound -> annotate(bound, expandGenerics, cache))
                    .toArray(AnnotatedType[]::new);
            annotatedCapture.setAnnotatedUpperBounds(upperBounds); //complete the type
            return annotatedCapture;
        }
        if (type instanceof WildcardType) {
            WildcardType wildcard = (WildcardType) type;
            AnnotatedType[] lowerBounds = stream(wildcard.getLowerBounds())
                    .map(bound -> annotate(bound, expandGenerics, cache))
                    .toArray(AnnotatedType[]::new);
            AnnotatedType[] upperBounds = stream(wildcard.getUpperBounds())
                    .map(bound -> annotate(bound, expandGenerics, cache))
                    .toArray(AnnotatedType[]::new);
            return new AnnotatedWildcardTypeImpl(wildcard, erase(type).getAnnotations(), lowerBounds, upperBounds);
        }
        if (type instanceof TypeVariable) {
            return new AnnotatedTypeVariableImpl((TypeVariable<?>) type);
        }
        if (type instanceof GenericArrayType) {
            GenericArrayType genArray = (GenericArrayType) type;
            return new AnnotatedArrayTypeImpl(genArray, new Annotation[0], annotate(genArray.getGenericComponentType(), expandGenerics, cache));
        }
        if (type instanceof Class) {
            Class<?> clazz = (Class<?>) type;
            if (clazz.isArray()) {
                Class<?> componentClass = clazz.getComponentType();
                return AnnotatedArrayTypeImpl.createArrayType(
                        new AnnotatedTypeImpl(componentClass, componentClass.getAnnotations()), new Annotation[0]);
            }
            if (clazz.getTypeParameters().length > 0 && expandGenerics) {
                return expandClassGenerics(clazz);
            }
            return new AnnotatedTypeImpl(clazz, clazz.getAnnotations());
        }
        throw new IllegalArgumentException("Unrecognized type: " + type.getTypeName());
    }

    /**
     * Creates a new {@link AnnotatedType} of the same type as the original, but with its annotations
     * replaced with the provided ones.
     *
     * @param original The type whose structure is to be copied
     * @param annotations Annotations to use instead of the ones found on the {@code original}
     *
     * @return A type of the same structure as the original but with replaced annotations
     */
    @SuppressWarnings("unchecked")
    public static <T extends AnnotatedType> T replaceAnnotations(T original, Annotation[] annotations) {
        if (original instanceof AnnotatedParameterizedType) {
            return (T) new AnnotatedParameterizedTypeImpl((ParameterizedType) original.getType(), annotations,
                    ((AnnotatedParameterizedType) original).getAnnotatedActualTypeArguments());
        }
        if (original instanceof AnnotatedCaptureType) {
            AnnotatedCaptureType capture = (AnnotatedCaptureType) original;
            return (T) new AnnotatedCaptureTypeImpl(
                    (CaptureType) capture.getType(),
                    capture.getAnnotatedWildcardType(),
                    capture.getAnnotatedTypeVariable(),
                    capture.getAnnotatedUpperBounds(),
                    annotations);
        }
        if (original instanceof AnnotatedWildcardType) {
            return (T) new AnnotatedWildcardTypeImpl((WildcardType) original.getType(), annotations,
                    ((AnnotatedWildcardType) original).getAnnotatedLowerBounds(),
                    ((AnnotatedWildcardType) original).getAnnotatedUpperBounds());
        }
        if (original instanceof AnnotatedTypeVariable) {
            return (T) new AnnotatedTypeVariableImpl((TypeVariable<?>) original.getType(), annotations);
        }
        if (original instanceof AnnotatedArrayType) {
            return (T) new AnnotatedArrayTypeImpl(original.getType(), annotations,
                    ((AnnotatedArrayType) original).getAnnotatedGenericComponentType());
        }
        return (T) new AnnotatedTypeImpl(original.getType(), annotations);
    }

    /**
     * Creates a new {@link AnnotatedType} of the same structure as the original, but with its annotations
     * replaced with the provided ones.
     *
     * @param original The type whose structure is to be copied
     * @param annotations Extra annotations to be added on top of the ones found on the {@code original}
     *
     * @return A type of the same structure as the original but with replaced annotations
     */
    public static <T extends AnnotatedType> T updateAnnotations(T original, Annotation[] annotations) {
        if (annotations == null || annotations.length == 0 || Arrays.equals(original.getAnnotations(), annotations)) {
            return original;
        }
        return replaceAnnotations(original, merge(original.getAnnotations(), annotations));
    }

    @SuppressWarnings("unchecked")
    public static <T extends AnnotatedType> T mergeAnnotations(T t1, T t2) {
        Annotation[] merged = merge(t1.getAnnotations(), t2.getAnnotations());
        if (t1 instanceof AnnotatedParameterizedType) {
            AnnotatedType[] p1 = ((AnnotatedParameterizedType) t1).getAnnotatedActualTypeArguments();
            AnnotatedType[] p2 = ((AnnotatedParameterizedType) t2).getAnnotatedActualTypeArguments();
            AnnotatedType[] params = new AnnotatedType[p1.length];
            for (int i = 0; i < p1.length; i++) {
                params[i] = mergeAnnotations(p1[i], p2[i]);
            }
            return (T) new AnnotatedParameterizedTypeImpl((ParameterizedType) t1.getType(), merged, params);
        }
        if (t1 instanceof AnnotatedWildcardType) {
            AnnotatedType[] l1 = ((AnnotatedWildcardType) t1).getAnnotatedLowerBounds();
            AnnotatedType[] l2 = ((AnnotatedWildcardType) t2).getAnnotatedLowerBounds();
            AnnotatedType[] lowerBounds = new AnnotatedType[l1.length];
            for (int i = 0; i < l1.length; i++) {
                lowerBounds[i] = mergeAnnotations(l1[i], l2[i]);
            }
            AnnotatedType[] u1 = ((AnnotatedWildcardType) t1).getAnnotatedUpperBounds();
            AnnotatedType[] u2 = ((AnnotatedWildcardType) t2).getAnnotatedUpperBounds();
            AnnotatedType[] upperBounds = new AnnotatedType[u1.length];
            for (int i = 0; i < u1.length; i++) {
                upperBounds[i] = mergeAnnotations(u1[i], u2[i]);
            }
            return (T) new AnnotatedWildcardTypeImpl((WildcardType) t1.getType(), merged, lowerBounds, upperBounds);
        }
        if (t1 instanceof AnnotatedTypeVariable) {
            return (T) new AnnotatedTypeVariableImpl((TypeVariable<?>) t1.getType(), merged);
        }
        if (t1 instanceof AnnotatedArrayType) {
            AnnotatedType componentType = mergeAnnotations(
                    ((AnnotatedArrayType) t1).getAnnotatedGenericComponentType(),
                    ((AnnotatedArrayType) t2).getAnnotatedGenericComponentType());
            return (T) new AnnotatedArrayTypeImpl(t1.getType(), merged, componentType);
        }
        return (T) new AnnotatedTypeImpl(t1.getType(), merged);
    }

    /**
     * Creates a new {@link AnnotatedParameterizedType} of the same raw class as the provided {@code type}
     * by with all of its type parameters replaced by {@code typeParameters}.
     * @param type The original parameterized type from which the raw class is to be taken
     * @param typeParameters The new type parameters to use
     *
     * @return The new parameterized type
     */
    public static AnnotatedParameterizedType replaceParameters(AnnotatedParameterizedType type, AnnotatedType[] typeParameters) {
        return replaceParameters(type, new Annotation[0], typeParameters);
    }

    private static AnnotatedParameterizedType replaceParameters(AnnotatedParameterizedType type, Annotation[] annotations, AnnotatedType[] typeParameters) {
        Type[] rawArguments = stream(typeParameters).map(AnnotatedType::getType).toArray(Type[]::new);
        ParameterizedType inner = (ParameterizedType) type.getType();
        ParameterizedType rawType = (ParameterizedType) TypeFactory.parameterizedInnerClass(inner.getOwnerType(), erase(inner), rawArguments);
        return new AnnotatedParameterizedTypeImpl(rawType, merge(type.getAnnotations(), annotations), typeParameters);
    }

    /**
     * Returns an {@link AnnotatedType} functionally identical to the given one, but in a canonical form that
     * implements {@code equals} and {@code hashCode}.
     *
     * @param type The type to turn into the canonical form
     *
     * @return A type functionally equivalent to the given one, but in the canonical form
     */
    public static <T extends AnnotatedType> T toCanonical(T type) {
        return toCanonical(type, Function.identity());
    }

    /**
     * Returns an {@link AnnotatedType} functionally identical to the given one, but in a canonical form that
     * implements {@code equals} and {@code hashCode} and has all the primitives replaced by their boxed form.
     *
     * @param type The type to turn into the canonical form
     *
     * @return A type functionally equivalent to the given one, but in the canonical form
     */
    public static <T extends AnnotatedType> T toCanonicalBoxed(T type) {
        return toCanonical(type, GenericTypeReflector::box);
    }

    /**
     * This is the method underlying {@link #toCanonical(AnnotatedType)}.
     * It recursively traverses the structure of the provided {@link AnnotatedType} turning all type parameters,
     * bounds etc. into their canonical forms, with a special treatment for {@link AnnotatedCaptureType}
     * which can have infinitely recursive structure by having itself as its upper bound.
     *
     * @param type The type to annotate
     * @param leafTransformer The transformer function to apply to leaf types (e.g. to box primitives)
     * @return Type whose structure has been recursively annotated
     *
     * <p>See {@link #toCanonical(AnnotatedType)}</p>
     * <p>See {@link AnnotatedCaptureCacheKey}</p>
     * <p>See {@link AnnotatedCaptureType}</p>
     * <p>See {@link CaptureType}</p>
     */
    @SuppressWarnings("unchecked")
    private static <T extends AnnotatedType> T toCanonical(T type, Function<Type, Type> leafTransformer) {
        return (T) transform(type, new TypeVisitor() {
            @Override
            protected AnnotatedType visitClass(AnnotatedType type) {
                Annotation[] annotations = type.getAnnotations();
                Class<?> raw = (Class<?>) type.getType();
                annotations = merge(annotations, raw.getAnnotations());
                return new AnnotatedTypeImpl(leafTransformer.apply(type.getType()), annotations);
            }

            @Override
            protected AnnotatedType visitArray(AnnotatedArrayType type) {
                return new AnnotatedArrayTypeImpl(leafTransformer.apply(type.getType()), type.getAnnotations(),
                        transform(type.getAnnotatedGenericComponentType(), this));
            }

            @Override
            protected AnnotatedType visitParameterizedType(AnnotatedParameterizedType type) {
                AnnotatedType[] params = Arrays.stream(type.getAnnotatedActualTypeArguments())
                        .map(param -> transform(param, this))
                        .toArray(AnnotatedType[]::new);

                Class<?> raw = (Class<?>)((ParameterizedType) type.getType()).getRawType();
                return GenericTypeReflector.replaceParameters(type, raw.getAnnotations(), params);
            }
        });
    }

    private static AnnotatedType expandGenerics(AnnotatedType type) {
        return transform(type, new TypeVisitor() {
            @Override
            public AnnotatedType visitClass(AnnotatedType type) {
                Class<?> clazz = (Class<?>) type.getType();
                if (clazz.getTypeParameters().length > 0) {
                    return expandClassGenerics(clazz);
                }
                return type;
            }
        });
    }

    /**
     * Recursively applies a transformation implemented by the provided {@code TypeVisitor}
     * to the structure of the given type.
     *
     * @param type The type to transform
     * @param visitor Visitor implementing the transformation
     * @return Type produced by recursively applying the transformation to the given type
     */
    public static AnnotatedType transform(AnnotatedType type, TypeVisitor visitor) {
        if (type instanceof AnnotatedParameterizedType) {
            return visitor.visitParameterizedType((AnnotatedParameterizedType) type);
        }
        if (type instanceof AnnotatedWildcardType) {
            return visitor.visitWildcardType((AnnotatedWildcardType) type);
        }
        if (type instanceof AnnotatedTypeVariable) {
            return visitor.visitVariable((AnnotatedTypeVariable) type);
        }
        if (type instanceof AnnotatedArrayType) {
            return visitor.visitArray((AnnotatedArrayType) type);
        }
        if (type instanceof AnnotatedCaptureType) {
            return visitor.visitCaptureType((AnnotatedCaptureType) type);
        }
        if (type.getType() instanceof Class) {
            return visitor.visitClass(type);
        }
        return visitor.visitUnmatched(type);
    }

    /**
     * Recursively traverses the structure of the given type, reducing all bounded types
     * ({@link AnnotatedTypeVariable}, {@link AnnotatedWildcardType} and {@link AnnotatedCaptureType})
     * to their first bound.
     *
     * @param type The type to transform
     * @return Type produced by recursively reducing bounded types within the structure of the given type
     */
    public static AnnotatedType reduceBounded(AnnotatedType type) {
        return transform(capture(type), new TypeVisitor() {
            @Override
            protected AnnotatedType visitVariable(final AnnotatedTypeVariable type) {
                return updateAnnotations(transform(type.getAnnotatedBounds()[0], this), type.getAnnotations());
            }

            @Override
            protected AnnotatedType visitWildcardType(AnnotatedWildcardType type) {
                return type.getAnnotatedLowerBounds().length > 0
                        ? updateAnnotations(transform(type.getAnnotatedLowerBounds()[0], this), type.getAnnotations())
                        : updateAnnotations(transform(type.getAnnotatedUpperBounds()[0],this), type.getAnnotations());
            }

            @Override
            protected AnnotatedType visitCaptureType(AnnotatedCaptureType type) {
                return type.getAnnotatedLowerBounds().length > 0
                        ? updateAnnotations(transform(type.getAnnotatedLowerBounds()[0], this), type.getAnnotations())
                        : updateAnnotations(transform(type.getAnnotatedUpperBounds()[0], this), type.getAnnotations());
            }
        });
    }

    private static AnnotatedParameterizedType expandClassGenerics(Class<?> type) {
        ParameterizedType inner = new ParameterizedTypeImpl(type, type.getTypeParameters(), type.getDeclaringClass());
        AnnotatedType[] params = stream(type.getTypeParameters()).map(GenericTypeReflector::annotate).toArray(AnnotatedType[]::new);
        return new AnnotatedParameterizedTypeImpl(inner, type.getAnnotations(), params);
    }

    /**
     * Merges an arbitrary number of annotations arrays, and removes duplicates.
     *
     * @param annotations Annotation arrays to merge and deduplicate
     *
     * @return An array containing all annotations from the given arrays, without duplicates
     */
    public static Annotation[] merge(Annotation[]... annotations) {
        int size = 0;
        for (Annotation[] annos : annotations) {
            size += annos.length;
        }
        Set<Annotation> result = new HashSet<>(size);
        for (Annotation[] annos : annotations) {
            for (Annotation anno : annos) {
                result.add(anno);
            }
        }
        return result.toArray(new Annotation[0]);
    }

    static boolean typeArraysEqual(AnnotatedType[] t1, AnnotatedType[] t2) {
        if (t1 == t2) return true;
        if (t1 == null) return false;
        if (t2 == null) return false;
        if (t1.length != t2.length) return false;

        for (int i = 0; i < t1.length; i++) {
            if (!t1[i].getType().equals(t2[i].getType()) || !Arrays.equals(t1[i].getAnnotations(), t2[i].getAnnotations())) {
                return false;
            }
        }
        return true;
    }

    public static int hashCode(AnnotatedType... types) {
        int typeHash = Arrays.stream(types)
                .mapToInt(t -> t.getType().hashCode())
                .reduce(0, (x,y) -> 127 * x ^ y);
        int annotationHash = hashCode(Arrays.stream(types)
                .flatMap(t -> Arrays.stream(t.getAnnotations())));
        return 31 * typeHash ^ annotationHash;
    }

    static int hashCode(Stream<Annotation> annotations) {
        return annotations
                .mapToInt(a -> 31 * a.annotationType().hashCode() ^ a.hashCode())
                .reduce(0, (x,y) -> 127 * x ^ y);
    }

    /**
     * Checks whether the two provided types are of the same structure and annotations on all levels.
     *
     * @param t1 The first type to be compared
     * @param t2 The second type to be compared
     *
     * @return True if both types have the same structure and annotations on all levels
     */
    public static boolean equals(AnnotatedType t1, AnnotatedType t2) {
        Objects.requireNonNull(t1);
        Objects.requireNonNull(t2);
        t1 = t1 instanceof AnnotatedTypeImpl ? t1 : toCanonical(t1);
        t2 = t2 instanceof AnnotatedTypeImpl ? t2 : toCanonical(t2);

        return t1.equals(t2);
    }

    /**
     * Helper method for getUpperBoundClassAndInterfaces, adding the result to the given set.
     */
    private static void buildUpperBoundClassAndInterfaces(Type type, Set<Class<?>> result) {
        if (type instanceof ParameterizedType || type instanceof Class<?>) {
            result.add(erase(type));
            return;
        }

        for (AnnotatedType superType : getExactDirectSuperTypes(annotate(type))) {
            buildUpperBoundClassAndInterfaces(superType.getType(), result);
        }
    }

    /**
     * A key representing a {@link CaptureType}. Used for caching incomplete {@link CaptureType}s
     * while recursively inspecting their structure. Necessary because {@link CaptureType} can have
     * infinitely recursive structure.
     *
     * <p>See {@link #annotate(Type, boolean, Map)}</p>
     */
    static class CaptureCacheKey {
        CaptureType capture;

        CaptureCacheKey(CaptureType capture) {
            this.capture = capture;
        }

        @Override
        public int hashCode() {
            return 127 * capture.getWildcardType().hashCode() ^ capture.getTypeVariable().hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof CaptureCacheKey)) return false;

            CaptureType that = ((CaptureCacheKey) obj).capture;
            return this.capture == that
                    || (capture.getWildcardType().equals(that.getWildcardType())
                    && capture.getTypeVariable().equals(that.getTypeVariable())
                    && Arrays.equals(capture.getUpperBounds(), that.getUpperBounds()));
        }
    }

    private static class AnnotatedCaptureCacheKey {
        AnnotatedCaptureType capture;
        CaptureType raw;

        AnnotatedCaptureCacheKey(AnnotatedCaptureType capture) {
            this.capture = capture;
            this.raw = (CaptureType) capture.getType();
        }

        @Override
        public int hashCode() {
            return 127 * raw.getWildcardType().hashCode() ^ raw.getTypeVariable().hashCode() ^ GenericTypeReflector.hashCode(Arrays.stream(capture.getAnnotations()));
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof AnnotatedCaptureCacheKey)) return false;

            AnnotatedCaptureCacheKey that = ((AnnotatedCaptureCacheKey) obj);
            return this.capture == that.capture ||
                    (new CaptureCacheKey(raw).equals(new CaptureCacheKey(that.raw))
                            && Arrays.equals(capture.getAnnotations(), that.capture.getAnnotations()));
        }
    }
}
