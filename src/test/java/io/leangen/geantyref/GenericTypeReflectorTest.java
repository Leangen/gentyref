/*
 * License: Apache License, Version 2.0
 * See the LICENSE file in the root directory or at <a href="http://www.apache.org/licenses/LICENSE-2">apache.org</a>.
 */

package io.leangen.geantyref;

import java.io.Serializable;
import java.lang.reflect.*;
import java.util.*;

import static io.leangen.geantyref.Annotations.*;
import static io.leangen.geantyref.Assertions.assertAnnotationsPresent;
import static io.leangen.geantyref.GenericTypeReflector.annotate;
import static io.leangen.geantyref.GenericTypeReflector.getExactSubType;
import static io.leangen.geantyref.GenericTypeReflector.resolveExactType;
import static io.leangen.geantyref.GenericTypeReflector.resolveType;

/**
 * Test for reflection done in GenericTypeReflector.
 * This class inherits most of its tests from the superclass, and adds a few more.
 */
public class GenericTypeReflectorTest extends AbstractGenericsReflectorTest {
    public GenericTypeReflectorTest() {
        super(new GenTyRefReflectionStrategy());
    }

    public void testGetTypeParameter() {
        class StringList extends ArrayList<String> {
        }
        assertEquals(String.class, GenericTypeReflector.getTypeParameter(StringList.class, Collection.class.getTypeParameters()[0]));
    }

    public void testGetUpperBoundClassAndInterfaces() {
        class Foo<A extends Number & Iterable<A>, B extends A> {
        }
        TypeVariable<?> a = Foo.class.getTypeParameters()[0];
        TypeVariable<?> b = Foo.class.getTypeParameters()[1];
        assertEquals(Arrays.asList(Number.class, Iterable.class),
                GenericTypeReflector.getUpperBoundClassAndInterfaces(a));
        assertEquals(Arrays.asList(Number.class, Iterable.class),
                GenericTypeReflector.getUpperBoundClassAndInterfaces(b));
    }

    /**
     * Call getExactReturnType with a method that is not a method of the given type.
     * Issue #6
     */
    public void testGetExactReturnTypeIllegalArgument() throws SecurityException, NoSuchMethodException {
        Method method = ArrayList.class.getMethod("set", int.class, Object.class);
        try {
            // ArrayList.set overrides List.set, but it's a different method so it's not a member of the List interface
            GenericTypeReflector.getExactReturnType(method, List.class);
            fail("expected exception");
        } catch (IllegalArgumentException e) { // expected
        }
    }

    /**
     * Same as {@link #testGetExactReturnTypeIllegalArgument()} for getExactFieldType
     */
    public void testGetExactFieldTypeShadowedFieldIllegalArgument() throws SecurityException, NoSuchFieldException {
        Field field = GummyWorm.class.getField("size");
        try {
            GenericTypeReflector.getExactFieldType(field, Gummy.class);
            fail("expected exception");
        } catch (IllegalArgumentException e) { // expected
        }
    }

    public void testGetExactFieldTypeIllegalArgument() throws SecurityException, NoSuchFieldException {
        Field field = Pen.class.getField("size");
        try {
            GenericTypeReflector.getExactFieldType(field, GummyWorm.class);
            fail("expected exception");
        } catch (IllegalArgumentException e) { // expected
        }
    }

    public void testGetExactParameterTypes() throws SecurityException, NoSuchMethodException {
        // method: boolean add(int index, E o), erasure is boolean add(int index, Object o)
        Method getMethod = List.class.getMethod("add", int.class, Object.class);
        Type[] result = GenericTypeReflector.getExactParameterTypes(getMethod, new TypeToken<ArrayList<String>>() {}.getType());
        assertEquals(2, result.length);
        assertEquals(int.class, result[0]);
        assertEquals(String.class, result[1]);
    }

    public void testGetExactConstructorParameterTypes() throws SecurityException, NoSuchMethodException {
        // constructor: D(T o), erasure is D(Object o)
        Constructor ctor = D.class.getDeclaredConstructor(Object.class);
        Type[] result = GenericTypeReflector.getExactParameterTypes(ctor, new TypeToken<D<String>>() {}.getType());
        assertEquals(1, result.length);
        assertEquals(String.class, result[0]);
    }

    public void testGetExactSubType() {
        AnnotatedParameterizedType parent = (AnnotatedParameterizedType) new TypeToken<P<String, Integer>>(){}.getAnnotatedType();
        AnnotatedParameterizedType subType = (AnnotatedParameterizedType) getExactSubType(parent, C.class);
        assertNotNull(subType);
        assertEquals(Integer.class, subType.getAnnotatedActualTypeArguments()[0].getType());
        assertEquals(String.class, subType.getAnnotatedActualTypeArguments()[1].getType());
    }

    public void testGetExactSubTypeIndirect() {
        AnnotatedParameterizedType parent = (AnnotatedParameterizedType) new TypeToken<P<List<String>, List<Map<String, Integer>>>>(){}.getAnnotatedType();
        AnnotatedParameterizedType subType = (AnnotatedParameterizedType) getExactSubType(parent, L.class);
        assertNotNull(subType);
        assertEquals(Integer.class, subType.getAnnotatedActualTypeArguments()[0].getType());
        assertEquals(String.class, subType.getAnnotatedActualTypeArguments()[1].getType());
    }

    public void testGetExactSubTypeShapeMismatch() {
        AnnotatedParameterizedType parent = (AnnotatedParameterizedType) new TypeToken<P<List<String>, Map<String, Integer>>>(){}.getAnnotatedType();
        AnnotatedParameterizedType subType = (AnnotatedParameterizedType) getExactSubType(parent, L.class);
        assertNull(subType);
    }

    public void testGetExactSubTypeShapeMismatch2() {
        AnnotatedParameterizedType parent = (AnnotatedParameterizedType) new TypeToken<P<Optional<String>, List<Map<String, Integer>>>>(){}.getAnnotatedType();
        AnnotatedParameterizedType subType = (AnnotatedParameterizedType) getExactSubType(parent, L.class);
        assertNull(subType);
    }

    public void testGetExactSubTypeUnresolvable() {
        AnnotatedParameterizedType parent = (AnnotatedParameterizedType) new TypeToken<P<String, Integer>>(){}.getAnnotatedType();
        AnnotatedType resolved = GenericTypeReflector.getExactSubType(parent, C1.class);
        assertNotNull(resolved);
        assertEquals(C1.class, resolved.getType());
    }

    public void testGetExactSubTypeUnresolvable2() {
        AnnotatedType parent = new TypeToken<N>(){}.getAnnotatedType();
        AnnotatedType resolved = GenericTypeReflector.getExactSubType(parent, C1.class);
        assertNotNull(resolved);
        assertEquals(C1.class, resolved.getType());
    }

    public void testGetExactSubTypeNotOverlapping() {
        AnnotatedParameterizedType parent = (AnnotatedParameterizedType) new TypeToken<List<String>>(){}.getAnnotatedType();
        AnnotatedType subType = getExactSubType(parent, Set.class);
        assertNull(subType);
    }

    public void testGetExactSubTypeNotParameterized() {
        AnnotatedParameterizedType parent = (AnnotatedParameterizedType) new TypeToken<List<String>>(){}.getAnnotatedType();
        AnnotatedType subType = getExactSubType(parent, String.class);
        assertNotNull(subType);
        assertEquals(String.class, subType.getType());
    }

    public void testGetExactSubTypeArray() {
        AnnotatedType parent = new TypeToken<List<String>[]>(){}.getAnnotatedType();
        AnnotatedType subType = getExactSubType(parent, ArrayList[].class);
        assertNotNull(subType);
        assertTrue(subType instanceof AnnotatedArrayType);
        AnnotatedType componentType = ((AnnotatedArrayType) subType).getAnnotatedGenericComponentType();
        assertTrue(componentType instanceof AnnotatedParameterizedType);
        assertEquals(String.class, ((AnnotatedParameterizedType) componentType).getAnnotatedActualTypeArguments()[0].getType());
    }

    public void testPartialReturnType() throws NoSuchMethodException {
        Type type = TypeFactory.parameterizedClass(Q.class, String.class);
        Method m = I.class.getDeclaredMethod("m", Object.class);
        Type returnType = GenericTypeReflector.getReturnType(m, type);
        assertTrue(returnType instanceof TypeVariable);
        assertEquals(String.class, ((TypeVariable) returnType).getBounds()[0]);
    }

    public void testPartialParameterType() throws NoSuchMethodException {
        Type type = TypeFactory.parameterizedClass(Q.class, Number.class);
        Method m = Q.class.getDeclaredMethod("m", Object.class);
        Type[] parameterTypes = GenericTypeReflector.getParameterTypes(m, type);
        assertTrue(parameterTypes[0] instanceof TypeVariable);
        assertEquals(Number.class, ((TypeVariable) parameterTypes[0]).getBounds()[0]);
    }

    public void testAnnotationMerging() {
        AnnotatedParameterizedType merged = GenericTypeReflector.mergeAnnotations((AnnotatedParameterizedType)t1, (AnnotatedParameterizedType)t2);
        assertAnnotationsPresent(merged, A1.class, A5.class);
        AnnotatedParameterizedType map = (AnnotatedParameterizedType) merged.getAnnotatedActualTypeArguments()[0];
        assertAnnotationsPresent(map, A2.class, A4.class);
        assertAnnotationsPresent(map.getAnnotatedActualTypeArguments()[0], A3.class, A2.class);
        AnnotatedArrayType intArray = (AnnotatedArrayType) map.getAnnotatedActualTypeArguments()[1];
        assertAnnotationsPresent(intArray, A5.class, A1.class);
        assertAnnotationsPresent(intArray.getAnnotatedGenericComponentType(), A4.class, A3.class);
    }

    public void testExactTypeResolution() throws NoSuchMethodException {
        Method m = W.class.getMethod("resolvable");
        AnnotatedType resolved = resolveExactType(m.getAnnotatedReturnType(), annotate(W.class));
        assertEquals(new TypeToken<Set<String[]>>() {}.getType(), resolved.getType());
        assertAnnotationsPresent(((AnnotatedArrayType)((AnnotatedParameterizedType) resolved)
                .getAnnotatedActualTypeArguments()[0]).getAnnotatedGenericComponentType(), A1.class, A2.class);
    }

    public void testPartialTypeResolution() throws NoSuchMethodException {
        Method m = W.class.getMethod("partiallyResolvable");
        AnnotatedType resolved = resolveType(m.getAnnotatedReturnType(), annotate(W.class));
        AnnotatedType key = ((AnnotatedParameterizedType) resolved).getAnnotatedActualTypeArguments()[0];
        AnnotatedType value = ((AnnotatedParameterizedType) resolved).getAnnotatedActualTypeArguments()[1];
        assertEquals(String.class, key.getType());
        assertAnnotationsPresent(key, A1.class, A2.class);
        assertEquals(W.class.getTypeParameters()[0], value.getType());
        assertAnnotationsPresent(value, A1.class, A3.class);
    }

    public void testCanonicalTypes() {
        AnnotatedType inner = new TypeToken<Outer.Inner>(){}.getAnnotatedType();
        AnnotatedType innerCanonical = GenericTypeReflector.toCanonical(inner);
        assertAnnotationsPresent(innerCanonical, A1.class);

        AnnotatedType innermost = new TypeToken<Outer.Inner.Innermost>(){}.getAnnotatedType();
        AnnotatedType innermostCanonical = GenericTypeReflector.toCanonical(innermost);
        assertAnnotationsPresent(innermostCanonical, A2.class);
    }

    public void testErasure() throws NoSuchFieldException {
        Type wildcard = GenericTypeReflector.addWildcardParameters(ComplexBounds.class);
        Type captureType = GenericTypeReflector.getExactFieldType(ComplexBounds.class.getField("u"), wildcard);
        assertEquals(Number.class, GenericTypeReflector.erase(captureType));
    }

    public void testReduceBounded() {
        AnnotatedType t = new TypeToken<ComplexBounds<Long, ?>>(){}.getAnnotatedType();
        AnnotatedParameterizedType reduced = (AnnotatedParameterizedType) GenericTypeReflector.reduceBounded(t);
        assertEquals(Long.class, reduced.getAnnotatedActualTypeArguments()[0].getType());
        assertEquals(Long.class, reduced.getAnnotatedActualTypeArguments()[1].getType());
    }

    public void testOwnerTypeResolution() throws NoSuchMethodException {
        AnnotatedType type = new TypeToken<Box<@A4 Integer>.Lock<@A5 Double>>() {}.getAnnotatedType();
        Method echo = Box.Lock.class.getDeclaredMethod("echo");
        AnnotatedType exactReturnType = GenericTypeReflector.getExactReturnType(echo, type);
        assertTrue(exactReturnType instanceof AnnotatedParameterizedType);
        AnnotatedParameterizedType parameterized = (AnnotatedParameterizedType) exactReturnType;
        assertEquals(Integer.class, parameterized.getAnnotatedActualTypeArguments()[0].getType());
        assertAnnotationsPresent(parameterized.getAnnotatedActualTypeArguments()[0], A4.class, A1.class, A2.class);
        AnnotatedParameterizedType owner = (AnnotatedParameterizedType) parameterized.getAnnotatedOwnerType();
        assertEquals(Integer.class, owner.getAnnotatedActualTypeArguments()[0].getType());
        assertAnnotationsPresent(owner.getAnnotatedActualTypeArguments()[0], A4.class, A1.class);
    }

    public void testOwnerTypeResolution2() throws NoSuchMethodException {
        AnnotatedType type = new TypeToken<Box<@A4 Integer>.Lock<@A5 Double>>() {}.getAnnotatedType();
        Method echo = Box.Lock.class.getDeclaredMethod("echo2");
        AnnotatedType exactReturnType = GenericTypeReflector.getExactReturnType(echo, type);
        assertTrue(exactReturnType instanceof AnnotatedParameterizedType);
        AnnotatedParameterizedType parameterized = (AnnotatedParameterizedType) exactReturnType;
        assertEquals(Double.class, parameterized.getAnnotatedActualTypeArguments()[0].getType());
        assertAnnotationsPresent(parameterized.getAnnotatedActualTypeArguments()[0], A5.class, A2.class);
        AnnotatedParameterizedType owner = (AnnotatedParameterizedType) parameterized.getAnnotatedOwnerType();
        assertEquals(Integer.class, owner.getAnnotatedActualTypeArguments()[0].getType());
        assertAnnotationsPresent(owner.getAnnotatedActualTypeArguments()[0], A4.class, A1.class);
    }

    public void testOwnerTypeResolution3() throws NoSuchMethodException {
        AnnotatedType type = new TypeToken<Box<@A4 Integer>.Shackle>() {}.getAnnotatedType();
        Method echo = Box.Shackle.class.getDeclaredMethod("echo2");
        AnnotatedType exactReturnType = GenericTypeReflector.resolveExactType(echo.getAnnotatedReturnType(), type);
        System.out.println(exactReturnType);
        assertTrue(exactReturnType instanceof AnnotatedParameterizedType);
        AnnotatedParameterizedType parameterized = (AnnotatedParameterizedType) exactReturnType;
        //Only parameterized because its owner is. No actual parameters of its own.
        assertEquals(0, parameterized.getAnnotatedActualTypeArguments().length);
        assertEquals(Box.Shackle.class, GenericTypeReflector.erase(parameterized.getType()));
        AnnotatedParameterizedType annotatedOwnerType = (AnnotatedParameterizedType) parameterized.getAnnotatedOwnerType();
        assertEquals(Box.class, GenericTypeReflector.erase(annotatedOwnerType.getType()));
        assertEquals(Integer.class, annotatedOwnerType.getAnnotatedActualTypeArguments()[0].getType());
        assertAnnotationsPresent(annotatedOwnerType.getAnnotatedActualTypeArguments()[0], A4.class, A1.class);
    }

    public void testArrayComponentType1() {
        // N is not an array class. Per the contract of the method, it should return null.
        // NOTE: This is a regression test for issue #32
        Type elementType = GenericTypeReflector.getArrayComponentType(N.class);
        assertEquals(null, elementType);
    }

    public void testArrayComponentType2() {
        // String[] is an array class. Per the contract of the method, it should return String.class.
        Type elementType = GenericTypeReflector.getArrayComponentType(String[].class);
        assertEquals(String.class, elementType);
    }

    public void testArrayComponentType3() {
        // gat1 is an generic array type. Per the contract of the method, it should return List<String>,
        // which is stored in gate1.
        Type elementType = GenericTypeReflector.getArrayComponentType(gat1);
        assertEquals(gate1, elementType);
    }

    private class N {}
    private class P<S, K> extends N {}
    private class L<S, K> extends P<List<K>, List<Map<K, S>>> {}
    private class M<U, R> extends P<U, R> {}
    private class C<X, Y> extends M<Y, X> {}
    private class C1<X, Y, Z> extends M<Y, X> {}
    private static class D<T> { D(T t) {}}
    private interface I<T> {<S extends T> S m(S s);}
    private static class Q<G> implements I<G> { @Override public <S extends G> S m(S s) { return null; }}

    private interface O<@A1 T, @A1 S> {
        default Set<@A2 T[]> resolvable() { return null; }
        default Map<@A2 T, @A3 S> partiallyResolvable() { return null; }
    }
    private static class W<T> implements O<String, T> {}
    private static class ComplexBounds<T extends Number & Serializable, U extends T> {public U u;}

    private static AnnotatedType t1 = new TypeToken<@A1 Optional<@A2 Map<@A3 String, @A4 Integer @A5 []>>>(){}.getAnnotatedType();
    private static AnnotatedType t2 = new TypeToken<@A5 Optional<@A4 Map<@A2 String, @A3 Integer @A1 []>>>(){}.getAnnotatedType();

    private static Type gat1 = new TypeToken<List<String> []>(){}.getType();
    private static Type gate1 = new TypeToken<List<String>>(){}.getType();

    private class Outer { @A1 class Inner { @A2 class Innermost {}}}

    public static class Pen {
        public int size;
    }
    public static class Gummy {
        public int size;
    }
    public static class GummyWorm extends Gummy {
        public int size;
    }

    class Box<@A1 T> {
        class Lock<@A2 S> {
            Lock<T> echo() {
                return null;
            }
            Lock<S> echo2() {
                return null;
            }
        }
        class Shackle extends Lock<Double> {
            Shackle echo2() {
                return this;
            }
        }
    }
}
